package main

import (
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
    "time"
    "github.com/gorilla/websocket"
)

func TestArtemisADBBridgeRequiresSecret(t *testing.T) {
    secret := strings.Repeat("a", 64)
    s, err := newServer(secret)
    if err != nil { t.Fatal(err) }
    b := &adbTransport{auth:s.auth}
    h := httptest.NewServer(http.HandlerFunc(b.phoneHandler))
    defer h.Close()
    u := "ws" + strings.TrimPrefix(h.URL, "http")
    ws, response, err := websocket.DefaultDialer.Dial(u, nil)
    if ws != nil { ws.Close() }
    if err == nil { t.Fatal("bridge accepted unauthenticated WebSocket") }
    if response == nil || response.StatusCode != http.StatusUnauthorized {
        t.Fatalf("expected 401, got %v: %v", response, err)
    }
}

func TestArtemisADBBridgePreservesBidirectionalBytes(t *testing.T) {
    secret := strings.Repeat("b", 64)
    s, err := newServer(secret)
    if err != nil { t.Fatal(err) }
    b := &adbTransport{auth:s.auth}
    endpoint := httptest.NewServer(http.HandlerFunc(b.phoneHandler))
    defer endpoint.Close()
    u := "ws" + strings.TrimPrefix(endpoint.URL, "http")
    headers := http.Header{"Authorization": []string{"Bearer " + secret}}
    ws, _, err := websocket.DefaultDialer.Dial(u, headers)
    if err != nil { t.Fatal(err) }
    defer ws.Close()

    listener, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil { t.Fatal(err) }
    defer listener.Close()
    go b.serveListener(listener)
    tcp, err := net.DialTimeout("tcp", listener.Addr().String(), 2*time.Second)
    if err != nil { t.Fatal(err) }
    defer tcp.Close()
    tcp.SetDeadline(time.Now().Add(5*time.Second))
    ws.SetReadDeadline(time.Now().Add(5*time.Second))

    kind, msg, err := ws.ReadMessage()
    if err != nil || kind != websocket.TextMessage || string(msg) != "open" {
        t.Fatalf("expected open, got %q %v %v", msg, kind, err)
    }
    if err := ws.WriteMessage(websocket.TextMessage, []byte("ready")); err != nil { t.Fatal(err) }

    source := []byte{0x00, 0x01, 0xff, 0x10, 0x0a}
    if _, err := tcp.Write(source); err != nil { t.Fatal(err) }
    kind, msg, err = ws.ReadMessage()
    if err != nil || kind != websocket.BinaryMessage || string(msg) != string(source) {
        t.Fatalf("TCP -> WS was not byte-exact: %x %v %v", msg, kind, err)
    }

    response := []byte{0x42, 0x00, 0xff, 0x99}
    if err := ws.WriteMessage(websocket.BinaryMessage, response); err != nil { t.Fatal(err) }
    received := make([]byte, len(response))
    if _, err := io.ReadFull(tcp, received); err != nil { t.Fatal(err) }
    if string(received) != string(response) {
        t.Fatalf("WS -> TCP was not byte-exact: %x", received)
    }
}
