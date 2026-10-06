// Command grpc-probe speaks the game client's own gRPC protocol to a running
// lunar-tear server and prints what the server tells a client to use.
//
// This is the one part of "serving the game" that HTTP checks cannot reach: the
// client's first real conversation is a gRPC call, and what it gets back (api
// host/port, Octo CDN URL) is what it will use for everything afterwards. So the
// probe asserts that the on-device server advertises the addresses the patched
// client was built for.
//
// It lives outside the app's Go module on purpose: it needs the server's
// generated protobuf stubs, which are gitignored in the upstream checkout.
//
// Usage:
//
//	adb forward tcp:18003 tcp:8003
//	go run . -addr 127.0.0.1:18003 -expect-host 127.0.0.1 -expect-port 8003 -expect-octo http://127.0.0.1:8080
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/types/known/emptypb"

	"lunar-tear/server/gen/proto"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:18003", "gRPC endpoint of the server under test")
	expectHost := flag.String("expect-host", "127.0.0.1", "hostname the server should advertise")
	expectPort := flag.Int("expect-port", 8003, "port the server should advertise")
	expectOcto := flag.String("expect-octo", "http://127.0.0.1:8080", "Octo CDN base URL the server should advertise")
	timeout := flag.Duration("timeout", 20*time.Second, "overall timeout")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	conn, err := grpc.NewClient(*addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		fail("dial %s: %v", *addr, err)
	}
	defer conn.Close()

	client := proto.NewConfigServiceClient(conn)
	response, err := client.GetReviewServerConfig(ctx, &emptypb.Empty{})
	if err != nil {
		fail("GetReviewServerConfig: %v", err)
	}

	fmt.Println("server answered GetReviewServerConfig:")
	if api := response.GetApi(); api != nil {
		fmt.Printf("  api        %s:%d\n", api.GetHostname(), api.GetPort())
	}
	if octo := response.GetOcto(); octo != nil {
		fmt.Printf("  octo       %s\n", octo.GetUrl())
	}
	if web := response.GetWebView(); web != nil {
		fmt.Printf("  webView    %s\n", web.GetBaseUrl())
	}
	if md := response.GetMasterData(); md != nil {
		fmt.Printf("  masterData urlFormat=%s\n", md.GetUrlFormat())
	}

	problems := 0
	check := func(name string, ok bool, got string) {
		if ok {
			fmt.Printf("  [PASS] %s = %s\n", name, got)
			return
		}
		problems++
		fmt.Printf("  [FAIL] %s = %s\n", name, got)
	}
	check("api hostname", response.GetApi().GetHostname() == *expectHost, response.GetApi().GetHostname())
	check("api port", int(response.GetApi().GetPort()) == *expectPort, fmt.Sprint(response.GetApi().GetPort()))
	check("octo url", response.GetOcto().GetUrl() == *expectOcto, response.GetOcto().GetUrl())

	if problems > 0 {
		fmt.Printf("\n%d checks FAILED\n", problems)
		os.Exit(1)
	}
	fmt.Println("\ngRPC protocol check passed")
}

func fail(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "grpc-probe: "+format+"\n", args...)
	os.Exit(1)
}
