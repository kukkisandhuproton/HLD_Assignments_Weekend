import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

/**
 * Exercise 04 — a terminal chat client using the WebSocket client built into the JDK.
 *
 *   java ChatClient.java ord_42 Ravi        # terminal A
 *   java ChatClient.java ord_42 Asha        # terminal B  (or use the browser page)
 *
 * Type a line + Enter to send it. Type /quit to leave.
 */
public class ChatClient {

    public static void main(String[] args) throws Exception {
        String order = args.length > 0 ? args[0] : "ord_42";
        String user = args.length > 1 ? args[1] : "terminal-" + (int) (Math.random() * 100);
        URI uri = URI.create("ws://localhost:8084/chat?order=" + enc(order) + "&user=" + enc(user));

        /*
         * TODO(5): Connect and chat.
         *   1. Open the socket:
         *        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
         *                         .buildAsync(uri, new PrintingListener()).join();
         *   2. Read lines from System.in. For each line:
         *        "/quit" -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join(); stop
         *        else    -> ws.sendText(<JSON {"type":"message","text":line}>, true).join();
         *      Escape quotes in the text!  (line.replace("\\", "\\\\").replace("\"", "\\\""))
         *
         *   Notice: there's no request/response here. You send whenever you like, and
         *   PrintingListener prints whatever the server pushes, whenever it pushes it.
         */
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, new PrintingListener()).join();
        System.out.println("Connected as " + user + ". Type a message or /quit.");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("/quit")) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                    break;
                }
                ws.sendText("{\"type\":\"message\",\"text\":" + escapeJson(line) + "}", true).join();
            }
        }
    }

    static String escapeJson(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (ch < 32) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.append('"').toString();
    }

    /** Called by the JDK whenever the server pushes something. (given) */
    static final class PrintingListener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket ws) {
            System.out.println("[open] handshake done -- HTTP is gone, we're on frames now");
            ws.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {                     // big messages can arrive in pieces
                System.out.println("<< " + partial);
                partial.setLength(0);
            }
            ws.request(1);                  // back-pressure: "I'm ready for one more message"
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            System.out.println("[closed] " + code + " " + reason);
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            System.out.println("[error] " + error);
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
