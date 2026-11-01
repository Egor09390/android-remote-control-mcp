package main
import (
 "net/http/httptest"
 "net/http"
 "strings"
 "testing"
)
func TestToken(t *testing.T){
 s,_:=newServer(strings.Repeat("a",64))
 r:=httptest.NewRequest("GET","http://localhost/agent",nil)
 if s.auth(r){t.Fatal("empty token accepted")}
 r.Header.Set("Authorization","Bearer "+strings.Repeat("a",64))
 if !s.auth(r){t.Fatal("correct token rejected")}
 r.Header.Set("Authorization","Bearer "+strings.Repeat("b",64))
 if s.auth(r){t.Fatal("wrong token accepted")}
}
func TestOffline(t *testing.T){
 s,_:=newServer(strings.Repeat("a",64))
 w:=httptest.NewRecorder()
 s.forward(w,httptest.NewRequest("POST","http://localhost/mcp",strings.NewReader("{}")))
 if w.Code!=http.StatusServiceUnavailable{t.Fatalf("got %d",w.Code)}
}
func TestPathsAndHeaders(t *testing.T){
 if !allowedPath("/mcp")||!allowedPath("/.well-known/oauth-authorization-server")||
 allowedPath("/agent")||allowedPath("/admin"){t.Fatal("path policy")}
 if safeHeader("X-Forwarded-Host")||safeHeader("Connection")||
 !safeHeader("Authorization"){t.Fatal("header policy")}
}
