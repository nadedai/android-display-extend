package io.github.jqssun.displayextend.shizuku;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/**
 * 统一执行 shell 命令:优先走 Shizuku(以 shell 身份运行),拿不到 Shizuku 权限时回退到 su(root)。
 *
 * <p>命令一律在后台线程执行,结果回到主线程回调,避免阻塞 UI。
 */
public class ShellExecutor {

  public interface Callback {
    void onResult(boolean success, String output);
  }

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  /** 当前是否已获得 Shizuku 权限(决定走 Shizuku 还是 su)。 */
  public static boolean hasShizukuPermission() {
    return ShizukuUtils.hasPermission();
  }

  public static void runAsync(String command, Callback callback) {
    EXECUTOR.execute(
        () -> {
          Result result = runSync(command);
          MAIN_HANDLER.post(
              () -> {
                if (callback != null) {
                  callback.onResult(result.success, result.output);
                }
              });
        });
  }

  public static Result runSync(String command) {
    Process process = null;
    try {
      process = _start(command);
      String output = _read(process.getInputStream()) + _read(process.getErrorStream());
      int code = process.waitFor();
      return new Result(code == 0, output.trim());
    } catch (Throwable e) {
      return new Result(false, String.valueOf(e.getMessage()));
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  private static Process _start(String command) throws Exception {
    if (ShizukuUtils.hasPermission()) {
      return Shizuku.newProcess(new String[] {"sh", "-c", command}, null, null);
    }
    // 没有 Shizuku 权限时尝试 root:设备已 root 的话这一步会弹出系统的 root 授权请求。
    return Runtime.getRuntime().exec(new String[] {"su", "-c", command});
  }

  private static String _read(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }

  public static class Result {
    public final boolean success;
    public final String output;

    public Result(boolean success, String output) {
      this.success = success;
      this.output = output;
    }
  }
}
