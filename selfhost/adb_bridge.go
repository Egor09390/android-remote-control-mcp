package main

// Artemis ADB transport over the existing authenticated reverse WebSocket tunnel.
// The TCP listeners are private to the Docker bridge network; do not publish them
// to the public internet. The phone must first enable/pair Android Wireless Debugging.
import (
    "io"
    "log"
    "net"
    "net/http"
    "sync"
    "time"
    "github.com/gorilla/websocket"
)

type adbTransport struct {
    auth func(*http.Request) bool
    mu sync.Mutex
    phone *websocket.Conn
    tcp net.Conn
    ready chan struct{}
    writeMu sync.Mutex
}

func (b *adbTransport) sendMessage(ws *websocket.Conn, kind int, data []byte) error {
    b.writeMu.Lock()
    defer b.writeMu.Unlock()
    _ = ws.SetWriteDeadline(time.Now().Add(20*time.Second))
    return ws.WriteMessage(kind, data)
}

func (b *adbTransport) phoneHandler(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodGet {
        http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
        return
    }
    if !b.auth(r) {
        http.Error(w, "unauthorized", http.StatusUnauthorized)
        return
    }
    ws, err := upgrader.Upgrade(w, r, nil)
    if err != nil { return }
    ws.SetReadLimit(1024*1024)
    b.mu.Lock()
    previous := b.phone
    oldTCP := b.tcp
    b.phone = ws
    b.tcp = nil
    b.ready = nil
    b.mu.Unlock()
    if previous != nil { _ = previous.Close() }
    if oldTCP != nil { _ = oldTCP.Close() }
    defer func() {
        b.mu.Lock()
        isActive := b.phone == ws
        var tcp net.Conn
        if isActive {
            b.phone = nil
            tcp = b.tcp
            b.tcp = nil
        }
        b.mu.Unlock()
        if tcp != nil { _ = tcp.Close() }
        _ = ws.Close()
    }()

    _ = ws.SetReadDeadline(time.Now().Add(120*time.Second))
    ws.SetPongHandler(func(_ string) error {
        return ws.SetReadDeadline(time.Now().Add(120*time.Second))
    })
    ws.SetPingHandler(func(payload string) error {
        _ = ws.SetReadDeadline(time.Now().Add(120*time.Second))
        return ws.WriteControl(websocket.PongMessage, []byte(payload), time.Now().Add(10*time.Second))
    })
    for {
        kind, bytes, err := ws.ReadMessage()
        if err != nil { return }
        _ = ws.SetReadDeadline(time.Now().Add(120*time.Second))
        if kind == websocket.TextMessage && string(bytes) == "ready" {
            b.mu.Lock()
            if b.phone == ws && b.ready != nil {
                close(b.ready)
                b.ready = nil
            }
            b.mu.Unlock()
            continue
        }
        if kind != websocket.BinaryMessage { continue }
        b.mu.Lock()
        tcp := b.tcp
        isActive := b.phone == ws
        b.mu.Unlock()
        if !isActive || tcp == nil { continue }
        // One WebSocket message equals one ordered piece of the ADB byte stream.
        if _, err := tcp.Write(bytes); err != nil {
            _ = tcp.Close()
        }
    }
}

func (b *adbTransport) serveTCP(address string) error {
    listener, err := net.Listen("tcp", address)
    if err != nil { return err }
    return b.serveListener(listener)
}

func (b *adbTransport) serveListener(listener net.Listener) error {
    defer listener.Close()
    log.Printf("Private Artemis ADB bridge listening on %s", listener.Addr())
    for {
        tcp, err := listener.Accept()
        if err != nil { return err }
        go b.handleTCP(tcp)
    }
}

func (b *adbTransport) handleTCP(tcp net.Conn) {
    b.mu.Lock()
    ws := b.phone
    if ws == nil || b.tcp != nil {
        b.mu.Unlock()
        _ = tcp.Close()
        return
    }
    b.tcp = tcp
    ready := make(chan struct{})
    b.ready = ready
    b.mu.Unlock()
    defer func() {
        _ = tcp.Close()
        b.mu.Lock()
        if b.tcp == tcp { b.tcp = nil; b.ready = nil }
        b.mu.Unlock()
        _ = b.sendMessage(ws, websocket.TextMessage, []byte("close"))
    }()

    if err := b.sendMessage(ws, websocket.TextMessage, []byte("open")); err != nil { return }
    // Never forward ADB bytes until the Android app confirms its local ADB socket is connected.
    select {
    case <-ready:
    case <-time.After(10*time.Second):
        log.Printf("Artemis local ADB connection did not become ready")
        return
    }
    buffer := make([]byte, 16*1024)
    for {
        count, err := tcp.Read(buffer)
        if count > 0 {
            if sendErr := b.sendMessage(ws, websocket.BinaryMessage, buffer[:count]); sendErr != nil {
                return
            }
        }
        if err != nil {
            if err != io.EOF { log.Printf("Artemis ADB transport closed: %v", err) }
            return
        }
    }
}
