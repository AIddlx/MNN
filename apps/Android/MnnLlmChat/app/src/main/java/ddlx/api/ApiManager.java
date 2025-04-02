package ddlx.api;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Binder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.alibaba.mnnllm.android.ChatSession;

public class ApiManager implements ServiceConnection {
    private static final String TAG = "ApiManager";
    private static volatile ApiManager instance;
    private final Context applicationContext;
    private boolean isModelLoaded = false;
    private ChatSession currentSession;
    private ApiStatusView statusView;
    private OpenAICompatibleService.ApiServiceBinder serviceBinder;
    private boolean isServiceBound = false;
    private int currentPort = 8080;

    // 添加 ChatSessionBinder 内部类
    public class ChatSessionBinder extends Binder {
        private final ChatSession session;

        public ChatSessionBinder(ChatSession session) {
            this.session = session;
        }

        public ChatSession getSession() {
            return session;
        }
    }

    private ApiManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.applicationContext = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.applicationContext);
        currentPort = prefs.getInt("api_port", 8080);
        Log.i(TAG, "ApiManager initialized with context");
    }

    /**
     * 获取ApiManager实例，需要提供Context进行初始化
     * 
     * @param context 上下文，用于初始化
     * @return ApiManager实例
     */
    public static ApiManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ApiManager.class) {
                if (instance == null) {
                    instance = new ApiManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 获取ApiManager实例，此方法仅在已初始化后可用
     * 如果实例未初始化，将尝试通过反射获取ApplicationContext
     * 
     * @return ApiManager实例
     */
    public static ApiManager getInstance() {
        if (instance == null) {
            synchronized (ApiManager.class) {
                if (instance == null) {
                    // 尝试通过反射获取ApplicationContext
                    Context appContext = getApplicationContextStatic();
                    if (appContext != null) {
                        instance = new ApiManager(appContext);
                    } else {
                        Log.e(TAG, "ApiManager not initialized and failed to get ApplicationContext");
                        throw new IllegalStateException("ApiManager not initialized. Call getInstance(Context) first");
                    }
                }
            }
        }
        return instance;
    }

    /**
     * 通过反射获取ApplicationContext的静态方法
     */
    private static Context getApplicationContextStatic() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Object app = activityThread.getMethod("getApplication").invoke(thread);
            if (app == null) {
                Log.e(TAG, "Failed to get application context through reflection");
                return null;
            }
            return (Context) app;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get application context: " + e.getMessage());
            return null;
        }
    }

    public Context getContext() {
        return applicationContext;
    }

    public void setStatusView(ApiStatusView view) {
        this.statusView = view;
    }

    public boolean isModelLoaded() {
        return isModelLoaded;
    }

    public void setCurrentSession(ChatSession session) {
        this.currentSession = session;
    }

    public ChatSession getCurrentSession() {
        return currentSession;
    }

    private Context getApplicationContext() {
        return getApplicationContextStatic();
    }

    /**
     * 一键初始化和配置 ApiManager
     * 
     * @param chatSession 聊天会话
     */
    public void setupWithSession(ChatSession chatSession) {
        Context applicationContext = getApplicationContext();
        if (applicationContext == null) {
            Log.e(TAG,
                    "Failed to get application context automatically, please call setupWithSession(chatSession, context) instead");
            return;
        }
        setCurrentSession(chatSession);
        setModelLoaded(true);
    }

    public void setModelLoaded(boolean loaded) {
        isModelLoaded = loaded;
        if (loaded) {
            Log.i(TAG, "Model loading completed, checking context...");
            if (applicationContext != null) {
                startApiService();
                Log.i(TAG, "Model loaded successfully, API service started");
            } else {
                Log.e(TAG, "Context not initialized, please call init() first");
            }
        } else {
            Log.e(TAG, "Model loading failed");
        }
    }

    public void startApiService() {
        if (applicationContext == null) {
            Log.e(TAG, "Context not initialized");
            return;
        }

        if (!isModelLoaded) {
            Log.e(TAG, "Model not loaded yet");
            return;
        }
        try {
            bindApiService();

            Intent intent = new Intent(applicationContext, OpenAICompatibleService.class);
            intent.putExtra("port", currentPort);
            applicationContext.startService(intent);

            if (statusView != null) {
                statusView.updateStatus(true);
            }
            Log.i(TAG, "OpenAI compatible API service started on port " + currentPort);
            Log.i(TAG, "Available endpoints:");
            Log.i(TAG, "  GET /v1/status - Check server and model status");
            Log.i(TAG, "  GET /v1/models - List available models");
            Log.i(TAG, "  POST /v1/chat/completions - Chat completion endpoint");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start API service: " + e.getMessage());
            isModelLoaded = false;
        }
    }

    public void updatePort(int port) {
        if (port != currentPort) {
            currentPort = port;
            if (isServiceBound) {
                stopApiService();
                startApiService();
            }
        }
    }

    private void bindApiService() {
        Intent intent = new Intent(applicationContext, OpenAICompatibleService.class);
        // 设置包名以支持跨应用调用
        intent.setPackage(applicationContext.getPackageName());
        applicationContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service instanceof OpenAICompatibleService.ApiServiceBinder) {
            serviceBinder = (OpenAICompatibleService.ApiServiceBinder) service;
            isServiceBound = true;
            Log.i(TAG, "API Service bound successfully");
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        serviceBinder = null;
        isServiceBound = false;
        Log.i(TAG, "API Service unbound");
    }

    public boolean isServiceBound() {
        return isServiceBound;
    }

    public OpenAICompatibleService.ApiServiceBinder getServiceBinder() {
        return serviceBinder;
    }

    public void stopApiService() {
        if (applicationContext == null) {
            Log.e(TAG, "Context not initialized");
            return;
        }
        if (isServiceBound) {
            applicationContext.unbindService(this);
            isServiceBound = false;
        }
        applicationContext.stopService(new Intent(applicationContext, OpenAICompatibleService.class));
        if (statusView != null) {
            statusView.updateStatus(false);
        }
        Log.i(TAG, "OpenAI compatible API service stopped");
    }

    public int getCurrentPort() {
        return currentPort;
    }

    /**
     * 处理Base64编码的图像数据，保存为文件并返回文件路径
     * 
     * @param base64Data Base64编码的图像数据
     * @return 保存的图像文件路径
     */
    public String processBase64Image(String base64Data) {
        if (applicationContext == null) {
            Log.e(TAG, "Context not initialized, cannot process image");
            return null;
        }
        return ImageFileManager.getInstance(applicationContext).processBase64Image(base64Data);
    }
}