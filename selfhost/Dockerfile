FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /relay ./main.go
FROM alpine:3.22
RUN adduser -D -u 10001 relay
USER relay
COPY --from=build /relay /relay
ENTRYPOINT ["/relay"]
