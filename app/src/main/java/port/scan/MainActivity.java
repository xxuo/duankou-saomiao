package port.scan;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
public class MainActivity extends Activity {
    // 核心配置：线程池大小改为10（平衡速度与稳定性），确保端口并行但IP串行
    private static final int THREAD_POOL_SIZE = 300, MAX_PORT = 65535, SOCKET_TIMEOUT = 1200;
    // UI控件
    private EditText etIp;
    private Button btnScan, btnStop;
    private TextView tvProgress, tvResult;
    // 扫描状态
    private ExecutorService scanExecutor;
    private final AtomicInteger scannedCount = new AtomicInteger(0);
    private final AtomicInteger totalTaskCount = new AtomicInteger(0);
    private final AtomicBoolean isScanRunning = new AtomicBoolean(false);
    private final List<String> openPorts = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread progressThread;
    private ClipboardManager clipboardManager;
    private List<String> targetIps = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        setupUI();
    }
    /**
     * 初始化UI（新增：统一输入框和按钮高度）
     */
    private void setupUI() {
        // 根布局（保持原逻辑）
        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        root.setBackgroundColor(0xFFF5F5F5);
        // 标题（保持原逻辑）
//        TextView title = new TextView(this);
//        LinearLayout.LayoutParams tP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
//        tP.bottomMargin = dp2px(5);
//        title.setLayoutParams(tP);
//        title.setText("端口扫描器");
//        title.setTextSize(24);
//        title.setTextColor(0xFF333333);
//        title.setGravity(Gravity.CENTER);
//        root.addView(title);
        // 输入区：新增统一高度逻辑
        LinearLayout inputContainer = new LinearLayout(this);
        LinearLayout.LayoutParams icP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icP.bottomMargin = dp2px(10);
        inputContainer.setLayoutParams(icP);
        inputContainer.setOrientation(LinearLayout.HORIZONTAL);
        inputContainer.setGravity(Gravity.CENTER_VERTICAL);
        // 定义统一高度（例如48dp）
        int uniformHeight = dp2px(33);
        // IP输入框：设置高度为uniformHeight
        etIp = new EditText(this);
        LinearLayout.LayoutParams etP = new LinearLayout.LayoutParams(0, uniformHeight, 1);
        etP.rightMargin = dp2px(10);
        etIp.setLayoutParams(etP);
        //etIp.setHint("目标IP/IP段（例：127.0.0.1 或 127.0.0.1-255）");
        etIp.setTextSize(13);
        etIp.setBackgroundResource(android.R.drawable.editbox_background);
        etIp.setText("127.0.0.1");
        //etIp.setTextIsSelectable(true);
        // 扫描按钮：设置高度为uniformHeight
        btnScan = new Button(this);
        LinearLayout.LayoutParams sbP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uniformHeight);
        sbP.rightMargin = dp2px(10);
        btnScan.setLayoutParams(sbP);
        btnScan.setText("开始扫描");
        btnScan.setTextSize(13);
        btnScan.setBackgroundColor(0xFF4CAF50);
        btnScan.setTextColor(0xFFFFFFFF);
        btnScan.setOnClickListener(v -> startScan());
        // 停止按钮：设置高度为uniformHeight
        btnStop = new Button(this);
        LinearLayout.LayoutParams stopP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uniformHeight);
        btnStop.setLayoutParams(stopP);
        btnStop.setText("停止扫描");
        btnStop.setTextSize(13);
        btnStop.setBackgroundColor(0xFFF44336);
        btnStop.setTextColor(0xFFFFFFFF);
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopScan());
        inputContainer.addView(etIp);
        inputContainer.addView(btnScan);
        inputContainer.addView(btnStop);
        root.addView(inputContainer);
        // 进度区（保持原逻辑）
        tvProgress = new TextView(this);
        LinearLayout.LayoutParams tpP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tpP.bottomMargin = dp2px(10);
        tvProgress.setLayoutParams(tpP);
        tvProgress.setText("准备就绪，请输入目标IP/IP段(192.168.1.1-3)");
        tvProgress.setTextSize(13);
        tvProgress.setTextColor(0xFF666666);
        tvProgress.setBackgroundColor(0xFFE8E8E8);
        tvProgress.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
        root.addView(tvProgress);
        // 结果区（保持原逻辑）
        tvResult = new TextView(this);
        LinearLayout.LayoutParams trP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        tvResult.setLayoutParams(trP);
        tvResult.setText("扫描结果将显示在这里...\n");
        tvResult.setTextSize(13);
        tvResult.setBackgroundColor(0xFFFFFFFF);
        tvResult.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        tvResult.setVerticalScrollBarEnabled(true);
        tvResult.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvResult.setTextIsSelectable(true);
        tvResult.setLongClickable(true);
        root.addView(tvResult);
        setContentView(root);
    }
    /**
     * 开始扫描（保持原逻辑）
     */
    private void startScan() {
        String input = etIp.getText().toString().trim();
        if (input.isEmpty()) {
            showToast("请输入目标IP或IP段！");
            return;
        }
        targetIps.clear();
        if (!parseIpInput(input)) {
            showToast("请输入有效的IP或IP段（例：127.0.0.1 或 127.0.0.1-255）！");
            return;
        }
        if (isScanRunning.get()) {
            showToast("扫描正在进行中！");
            return;
        }
        scannedCount.set(0);
        totalTaskCount.set(targetIps.size() * MAX_PORT);
        openPorts.clear();
        mainHandler.post(() -> {
            tvResult.setText("");
            tvProgress.setText("初始化扫描...");
        });
        isScanRunning.set(true);
        updateButtonState();
        appendResult("🚀 开始扫描目标: " + input + " (逐个IP扫描，每个IP端口 1-" + MAX_PORT + ")\n========================================\n");
        startProgressThread();
        startPortScanning();
    }
    /**
     * 解析IP输入（保持原逻辑）
     */
    private boolean parseIpInput(String input) {
        String ipRangeRegex = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)-(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?))$";
        String singleIpRegex = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        if (input.matches(singleIpRegex)) {
            targetIps.add(input);
            return true;
        } else if (input.matches(ipRangeRegex)) {
            String[] parts = input.split("\\.");
            String prefix = parts[0] + "." + parts[1] + "." + parts[2] + ".";
            String rangePart = parts[3];
            String[] range = rangePart.split("-");
            int start = Integer.parseInt(range[0]);
            int end = Integer.parseInt(range[1]);
            if (start < 0 || end > 255 || start > end) {
                return false;
            }
            for (int i = start; i <= end; i++) {
                targetIps.add(prefix + i);
            }
            return true;
        } else {
            return false;
        }
    }
    /**
     * 停止扫描（保持原逻辑）
     */
    private void stopScan() {
        if (!isScanRunning.get()) return;
        isScanRunning.set(false);
        if (progressThread != null && progressThread.isAlive()) progressThread.interrupt();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            try {
                if (!scanExecutor.awaitTermination(2, TimeUnit.SECONDS)) scanExecutor.shutdownNow();
            } catch (InterruptedException e) {
                scanExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        appendResult("\n⏹️ 扫描已停止\n已完成任务: " + scannedCount.get() + "/" + totalTaskCount.get() + "\n发现开放端口: " + openPorts.size() + "\n");
        updateButtonState();
        showToast("扫描已停止");
    }
    /**
     * 进度更新线程（保持原逻辑）
     */
    private void startProgressThread() {
        progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && isScanRunning.get()) {
                try {
                    mainHandler.post(() -> tvProgress.setText(String.format("扫描中: 已完成 %5d/%d 任务 | 开放端口: %d",
                                                                            scannedCount.get(), totalTaskCount.get(), openPorts.size())));
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!isScanRunning.get()) {
                mainHandler.post(() -> tvProgress.setText(String.format("扫描已停止! 已完成 %d/%d 任务 | 开放端口: %d",
                                                                        scannedCount.get(), totalTaskCount.get(), openPorts.size())));
            }
        });
        progressThread.start();
    }
    /**
     * 端口扫描：核心修改！逐个IP扫描（扫完一个IP再扫下一个）
     */
    private void startPortScanning() {
        scanExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        new Thread(() -> {
            try {
                // 遍历每个IP，强制顺序执行
                for (String ip : targetIps) {
                    if (!isScanRunning.get()) break;
                    // 输出当前扫描的IP，确保先显示这个再开始扫端口
                    appendResult("\n📌 开始扫描IP: " + ip + "（共" + MAX_PORT + "个端口）\n");
                    // 用CountDownLatch等待当前IP的所有端口扫描完成
                    CountDownLatch ipLatch = new CountDownLatch(MAX_PORT);
                    // 提交当前IP的所有端口任务
                    for (int port = 1; port <= MAX_PORT && isScanRunning.get(); port++) {
                        final int p = port;
                        final String targetIp = ip;
                        scanExecutor.submit(() -> {
                            if (isScanRunning.get()) scanPort(targetIp, p);
                            ipLatch.countDown(); // 每个端口任务完成后计数-1
                        });
                    }
                    // 阻塞等待当前IP的所有端口扫描完成（超时30分钟，防止无限等待）
                    try {
                        ipLatch.await(30, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // 当前IP扫描完成，输出提示
                    appendResult("\n✅ IP: " + ip + " 扫描完成！\n");
                }
                // 所有IP扫描完成后，关闭线程池并处理超时
                scanExecutor.shutdown();
                boolean terminated = false;
                try {
                    terminated = scanExecutor.awaitTermination(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    scanExecutor.shutdownNow();
                }
                if (terminated && isScanRunning.get()) {
                    mainHandler.post(() -> {
                        isScanRunning.set(false);
                        updateButtonState();
                        showScanComplete();
                    });
                }
            } finally {
                if (isScanRunning.get()) {
                    isScanRunning.set(false);
                    mainHandler.post(this::updateButtonState);
                }
            }
        }).start();
    }
    /**
     * 单端口扫描（保持原逻辑，含超时修复）
     */
    private void scanPort(String ip, int port) {
        if (!isScanRunning.get()) {
            scannedCount.incrementAndGet();
            return;
        }
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), SOCKET_TIMEOUT);
            socket.setSoTimeout(SOCKET_TIMEOUT); // 读取超时，避免阻塞
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + ip + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[512];
            int len = in.read(buf);
            String resp = len > 0 ? new String(buf, 0, len, StandardCharsets.UTF_8).trim().replace("\r\n", " ") : "";
            String info = String.format("✅ %s:%d: %s", ip, port, resp.isEmpty() ? "有连接无响应" : resp);
			
            openPorts.add(info);
            appendResult(info + "\n");
        } catch (ConnectException | SocketTimeoutException e) {
            // 端口关闭、连接超时、读取超时，直接忽略
        } catch (IOException e) {
            // 其他IO异常，忽略
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException e) {}
            scannedCount.incrementAndGet(); // 确保任务计数
        }
    }
    /**
     * 扫描完成提示（适配逐个IP统计）
     */
    private void showScanComplete() {
        appendResult("\n========================================\n🎉 全部扫描完成！\n\n📊 扫描统计:\n" +
                     "   目标范围: " + etIp.getText().toString() + "\n   扫描IP数: " + targetIps.size() + "\n" +
                     "   每个IP端口: 1-" + MAX_PORT + "\n   总任务数: " + totalTaskCount.get() + "\n" +
                     "   已完成: " + scannedCount.get() + " 个任务\n   开放端口: " + openPorts.size() + " 个\n\n");
        if (openPorts.isEmpty()) {
            appendResult("❌ 未发现开放端口\n");
        } else {
            appendResult("📋 开放端口汇总:\n");
            for (String info : openPorts) appendResult(info + "\n");
        }
    }
    /**
     * 更新按钮状态（保持原逻辑）
     */
    private void updateButtonState() {
        mainHandler.post(() -> {
            boolean running = isScanRunning.get();
            btnScan.setEnabled(!running);
            btnStop.setEnabled(running);
            etIp.setEnabled(!running);
            btnScan.setBackgroundColor(running ? 0xFFCCCCCC : 0xFF4CAF50);
            btnStop.setBackgroundColor(running ? 0xFFF44336 : 0xFFCCCCCC);
        });
    }
    /**
     * 追加结果（保持原逻辑）
     */
    private void appendResult(String content) {
        mainHandler.post(() -> {
            tvResult.append(content);
            int scroll = tvResult.getLayout().getLineTop(tvResult.getLineCount()) - tvResult.getHeight();
            if (scroll > 0) tvResult.scrollTo(0, scroll);
        });
    }
    /**
     * 显示Toast（保持原逻辑）
     */
    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }
    /**
     * dp转px（保持原逻辑）
     */
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isScanRunning.get()) stopScan();
        if (scanExecutor != null && !scanExecutor.isShutdown()) scanExecutor.shutdownNow();
    }
}
