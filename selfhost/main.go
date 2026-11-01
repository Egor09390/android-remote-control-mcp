package main

import (
 "context"
 "crypto/rand"
 "crypto/subtle"
 "encoding/base64"
 "errors"
 "flag"
 "fmt"
 "io"
 "log"
 "net/http"
 "os"
 "strings"
 "sync"
 "time"
 "github.com/gorilla/websocket"
)

const (
 maxBody=12<<20
 maxMessage=24<<20
 maxConcurrent=8
 requestTimeout=180*time.Second
)
type packet struct {
 Type string `json:"type"`
 ID string `json:"id"`
 Method string `json:"method,omitempty"`
 Path string `json:"path,omitempty"`
 Headers map[string]string `json:"headers,omitempty"`
 Body string `json:"body,omitempty"`
 Status int `json:"status,omitempty"`
}
type agent struct {conn *websocket.Conn; mu sync.Mutex}
func(a *agent) send(p packet)error{
 a.mu.Lock();defer a.mu.Unlock()
 _=a.conn.SetWriteDeadline(time.Now().Add(20*time.Second))
 return a.conn.WriteJSON(p)
}
type server struct{secret string; mu sync.Mutex; active *agent; pending map[string]chan packet}
var upgrader=websocket.Upgrader{
 ReadBufferSize:8192,WriteBufferSize:8192,
 CheckOrigin:func(r *http.Request)bool{return r.Header.Get("Origin")==""},
}
func newServer(secret string)(*server,error){
 if len(secret)<32{return nil,errors.New("MCP_TUNNEL_TOKEN must have >=32 characters")}
 return &server{secret:secret,pending:make(map[string]chan packet)},nil
}
func(s *server)auth(r *http.Request)bool{
 h:=r.Header.Get("Authorization")
 if !strings.HasPrefix(h,"Bearer "){return false}
 v:=strings.TrimPrefix(h,"Bearer ")
 return len(v)==len(s.secret)&&subtle.ConstantTimeCompare([]byte(v),[]byte(s.secret))==1
}
func(s *server)connect(w http.ResponseWriter,r *http.Request){
 if r.Method!=http.MethodGet{http.Error(w,"method not allowed",405);return}
 if !s.auth(r){http.Error(w,"unauthorized",401);return}
 conn,err:=upgrader.Upgrade(w,r,nil);if err!=nil{return}
 conn.SetReadLimit(maxMessage)
 a:=&agent{conn:conn}
 s.mu.Lock();old:=s.active;s.active=a;s.mu.Unlock()
 if old!=nil{_=old.conn.Close()}
 log.Println("phone connected")
 defer func(){
  s.mu.Lock();if s.active==a{s.active=nil};s.mu.Unlock()
  _=conn.Close();log.Println("phone disconnected")
 }()
 _=conn.SetReadDeadline(time.Now().Add(100*time.Second))
 conn.SetPongHandler(func(string)error{return conn.SetReadDeadline(time.Now().Add(100*time.Second))})
 conn.SetPingHandler(func(payload string)error{
   _=conn.SetReadDeadline(time.Now().Add(100*time.Second))
   return conn.WriteControl(websocket.PongMessage,[]byte(payload),time.Now().Add(10*time.Second))
 })
 for{
  var p packet
  if err:=conn.ReadJSON(&p);err!=nil{return}
  _=conn.SetReadDeadline(time.Now().Add(100*time.Second))
  if p.Type!="response"||p.ID==""{continue}
  s.mu.Lock();ch:=s.pending[p.ID];if s.active!=a{ch=nil};s.mu.Unlock()
  if ch!=nil{select{case ch<-p:default:}}
 }
}
func newID()string{
 b:=make([]byte,16);if _,err:=rand.Read(b);err!=nil{panic(err)}
 return base64.RawURLEncoding.EncodeToString(b)
}
func allowedPath(p string)bool{
 if p=="/mcp"||p=="/health"||p=="/authorize"||p=="/authorize/status"||
 p=="/token"||p=="/register"||p=="/"{return true}
 return strings.HasPrefix(p,"/.well-known/")||strings.HasPrefix(p,"/s/")
}
func safeHeader(k string)bool{
 switch http.CanonicalHeaderKey(k){
 case "Connection","Upgrade","Proxy-Connection","Keep-Alive","Transfer-Encoding",
 "Te","Trailer","Proxy-Authenticate","Proxy-Authorization","Host","Content-Length",
 "X-Forwarded-Proto","X-Forwarded-Host","X-Forwarded-For":
  return false
 }
 return true
}
func(s *server)forward(w http.ResponseWriter,r *http.Request){
 if !allowedPath(r.URL.Path){http.NotFound(w,r);return}
 if r.Method==http.MethodConnect{http.Error(w,"method not allowed",405);return}
 body,err:=io.ReadAll(http.MaxBytesReader(w,r.Body,maxBody))
 if err!=nil{http.Error(w,"request too large",413);return}
 headers:=map[string]string{}
 for k,v:=range r.Header{if safeHeader(k)&&len(v)>0{headers[http.CanonicalHeaderKey(k)]=v[0]}}
 headers["X-Forwarded-Proto"]="https"
 headers["X-Forwarded-Host"]=r.Host
 p:=packet{Type:"request",ID:newID(),Method:r.Method,Path:r.URL.RequestURI(),Headers:headers,
  Body:base64.StdEncoding.EncodeToString(body)}
 ch:=make(chan packet,1)
 s.mu.Lock()
 a:=s.active
 if a==nil{s.mu.Unlock();http.Error(w,"phone offline",503);return}
 if len(s.pending)>=maxConcurrent{s.mu.Unlock();http.Error(w,"busy",429);return}
 s.pending[p.ID]=ch;s.mu.Unlock()
 defer func(){s.mu.Lock();delete(s.pending,p.ID);s.mu.Unlock()}()
 if err:=a.send(p);err!=nil{http.Error(w,"phone connection lost",503);return}
 ctx,cancel:=context.WithTimeout(r.Context(),requestTimeout);defer cancel()
 select{
 case resp:=<-ch:
  data,err:=base64.StdEncoding.DecodeString(resp.Body)
  if err!=nil||len(data)>maxBody{http.Error(w,"bad phone response",502);return}
  for k,v:=range resp.Headers{if safeHeader(k){w.Header().Set(k,v)}}
  status:=resp.Status;if status<100||status>599{status=502}
  w.WriteHeader(status);_,_=w.Write(data)
 case <-ctx.Done():
  if errors.Is(ctx.Err(),context.DeadlineExceeded){http.Error(w,"phone timeout",504)}
 }
}
func main(){
 listen:=flag.String("listen",":18080","Docker-internal listener, never publish externally")
 flag.Parse()
 s,err:=newServer(os.Getenv("MCP_TUNNEL_TOKEN"));if err!=nil{log.Fatal(err)}

 mux:=http.NewServeMux()
 mux.HandleFunc("/agent",s.connect);mux.HandleFunc("/",s.forward)
 server:=&http.Server{Addr:*listen,Handler:mux,ReadHeaderTimeout:10*time.Second,
  IdleTimeout:120*time.Second,MaxHeaderBytes:1<<20}
 fmt.Printf("MCP relay listening on %s\n",*listen)
 log.Fatal(server.ListenAndServe())
}
