package ddlx.api;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import com.alibaba.mls.api.ApplicationProvider;
import com.alibaba.mnnllm.android.ChatSession;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class OpenAIService extends Service {
    // private static final String TAG = "OpenAIService";
    private static final int PORT = 8080;
    private static volatile OpenAIService instance;
    private ApiServer server;
    private static ChatSession currentSession;
    public static OpenAIService getInstance(Context context ) {
        if (instance == null) {
              context.startService(new Intent(context, OpenAIService.class));
        }
        return instance;
    }

    public static void setCurrentSession(ChatSession session) {
        currentSession = session;
    }


    public static ChatSession getCurrentSession() {
        return currentSession;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (server != null) {
                server.stop();
            }
            server = new ApiServer();
            server.start();
            Intent readyIntent = new Intent("com.alibaba.mnnllm.android.SERVICE_READY");
            sendBroadcast(readyIntent);
        } catch (IOException e) {
            Intent failureIntent = new Intent("com.alibaba.mnnllm.android.SERVICE_FAILED");
            failureIntent.putExtra("error", e.getMessage());
            sendBroadcast(failureIntent);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    private class ApiServer extends NanoHTTPD {
        ApiServer() throws IOException {
            super("0.0.0.0", PORT);
        }

        @Override
        public Response serve(IHTTPSession session) {
            if (Method.OPTIONS.equals(session.getMethod())) {
                return addCorsHeaders(newFixedLengthResponse(""));
            }

            try {
                String uri = session.getUri();
                if ("/v1/chat/completions".equals(uri)) {
                    return addCorsHeaders(handleChatCompletions(session));
                }
                return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "application/json", "{\"error\":\"Not found\"}"));
            } catch (Exception e) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json", "{\"error\":\"Internal server error\"}"));
            }
        }

        private Response handleChatCompletions(IHTTPSession session) throws Exception {
            // String method = session.getMethod().toString();
            if (!Method.POST.equals(session.getMethod())) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                        "application/json", "{\"error\":\"Method not allowed\"}");
            }

            ChatSession chatSession = getCurrentSession();
            if (chatSession == null) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                        "application/json", "{\"error\":\"No active chat session\"}");
            }

            chatSession.reset(); // 确保清空之前的对话状态
            byte[] buffer = new byte[session.getInputStream().available()];
            session.getInputStream().read(buffer);
            JSONObject request = new JSONObject(new String(buffer, StandardCharsets.UTF_8));

            StringBuilder context = new StringBuilder();
            JSONArray messages = request.getJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                context.append(message.getString("role"))
                        .append(": ")
                        .append(message.getString("content"))
                        .append("\n");
            }

            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);
            Response response = newChunkedResponse(Response.Status.OK, "text/event-stream", in);
            response.addHeader("Content-Type", "text/event-stream");
            response.addHeader("Cache-Control", "no-cache");

            new Thread(() -> {
                try {
                    String responseId = "catchall-" + System.currentTimeMillis();
                    chatSession.generate(context.toString(), progress -> {
                        try {
                            if (progress != null) {
                                JSONObject chunk = new JSONObject()
                                        .put("id", responseId)
                                        .put("object", "chat.completion.chunk")
                                        .put("created", System.currentTimeMillis() / 1000)
                                        .put("model", "mnn-local")
                                        .put("choices", new JSONArray()
                                                .put(new JSONObject()
                                                        .put("delta", new JSONObject()
                                                                .put("content", progress))
                                                        .put("index", 0)
                                                        .put("finish_reason", null)));
                                out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (Exception e) {
                            return true;
                        }
                        return false;
                    });
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Ignore stream errors
                } finally {
                    try {
                        out.close();
                        chatSession.reset(); // 请求处理完成后再次调用reset()确保状态清除
                    } catch (IOException e) {
                        // Ignore close errors
                    }
                }
            }).start();

            return response;
        }

        private Response addCorsHeaders(Response response) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "*");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        }
    }
}