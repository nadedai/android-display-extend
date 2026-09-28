package io.github.jqssun.displayextend.shizuku;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.jqssun.displayextend.State;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/**
 * 统一执行 shell 命令:优先走 Shizuku 的 {@link UserService}(以 shell 身份运行),拿不到 Shizuku 权限时
 * 回退到 {@code su}(root),这一步会触发系统的 root 授权请求。
 *
 * <p>命令一律在后台线程执行,结果回到主线程回调,避免阻塞 UI。
 *
 * <p>注意:Shizuku 的 {@code Shizuku.newProcess} 在 13.x 里是包内私有,不能直接调用,所以走
 * {@link UserService#execCommand} —— 那个进程本身就是 Shizuku 以 shell(uid 2000)身份拉起的,
 * 在里面 {@code Runtime.exec} 等价于在 adb shell 里执行。
 */
public class ShellExecutor {

  public interface Callback {
    void onResult(boolean success, String output);
  }

  private static final String TAG = "ShellExecutor";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  /** 等待 UserService 绑定完成的最长时间(毫秒)。 */
  private static final long USER_SERVICE_WAIT_MILLIS = 5000;
  private static final long USER_SERVICE_POLL_MILLIS = 100;
  /** su 命令的最长等待时间(秒);用户可能要在授权框上点确认,所以给得宽一些。 */
  private static final long ROOT_TIMEOUT_SECONDS = 60;

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
    if (ShizukuUtils.hasPermission()) {
      IUserService service = _ensureUserService();
      if (service != null) {
        try {
          return _parseExecResult(service.execCommand(command));
        } catch (Throwable e) {
          // UserService 调用失败(被回收/连接断了),退回 root 兜底
          Log.w(TAG, "shizuku exec failed, falling back to su: " + command, e);
        }
      } else {
        Log.w(TAG, "user service unavailable, falling back to su: " + command);
      }
    }
    return _runWithSu(command);
  }

  /** 解析 {@link UserService#execCommand} 返回的 {@code "<exitCode>\n<output>"}。 */
  private static Result _parseExecResult(String raw) {
    if (raw == null) {
      return new Result(false, "");
    }
    int newline = raw.indexOf('\n');
    if (newline < 0) {
      return new Result(false, raw.trim());
    }
    int exitCode;
    try {
      exitCode = Integer.parseInt(raw.substring(0, newline).trim());
    } catch (NumberFormatException e) {
      exitCode = -1;
    }
    return new Result(exitCode == 0, raw.substring(newline + 1).trim());
  }

  /**
   * 拿到可用的 UserService 代理。已经绑定好就直接用;没绑定就发起绑定并等一会儿
   * (绑定是异步的,由 Shizuku 回调 {@code State.userServiceConnection} 后赋值)。
   */
  private static IUserService _ensureUserService() {
    IUserService service = State.userService;
    if (service != null) {
      return service;
    }
    try {
      Shizuku.peekUserService(State.userServiceArgs, State.userServiceConnection);
      Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection);
    } catch (Throwable e) {
      Log.w(TAG, "failed to bind user service", e);
      return null;
    }
    long deadline = System.currentTimeMillis() + USER_SERVICE_WAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      service = State.userService;
      if (service != null) {
        return service;
      }
      try {
        Thread.sleep(USER_SERVICE_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return State.userService;
  }

  /** 没有 Shizuku 权限时的兜底:走 root。设备已 root 的话这一步会弹出系统的 root 授权请求。 */
  private static Result _runWithSu(String command) {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
      builder.redirectErrorStream(true);
      process = builder.start();
      final Process running = process;
      final StringBuilder buffer = new StringBuilder();
      Thread readerThread =
          new Thread(
              () -> {
                try (BufferedReader stream =
                    new BufferedReader(
                        new InputStreamReader(
                            running.getInputStream(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = stream.readLine()) != null) {
                    buffer.append(line).append('\n');
                  }
                } catch (Exception ignored) {
                  // 进程被销毁时读流会抛异常,忽略即可
                }
              });
      readerThread.start();
      if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Result(false, "执行超时,可能未授予 root 权限");
      }
      readerThread.join(2000);
      return new Result(process.exitValue() == 0, buffer.toString().trim());
    } catch (Throwable e) {
      return new Result(false, String.valueOf(e.getMessage()));
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
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
