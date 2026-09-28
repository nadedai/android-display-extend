package io.github.jqssun.displayextend;

import io.github.jqssun.displayextend.shizuku.ShellExecutor;

/**
 * 用 RRO overlay 把 framework 的 {@code config_localDisplaysMirrorContent} 改成 false,
 * 让内置屏的画面不再被镜像到外接显示器。
 *
 * <p>等价于下面这两条命令(以 shell 或 root 身份执行):
 *
 * <pre>
 *   cmd overlay fabricate --target android --name noMirror \
 *       android:bool/config_localDisplaysMirrorContent 0x12 0x0
 *   cmd overlay enable --user 0 com.android.shell:noMirror
 * </pre>
 *
 * <p>取消时只需 {@code cmd overlay disable --user 0 com.android.shell:noMirror}。
 * fabricate 出来的 overlay 归属于 shell 包,所以包名固定是 {@code com.android.shell:noMirror},
 * 用 Shizuku 还是 root 执行都一样。
 */
public class NoMirrorOverlay {

  /** fabricate 生成的 overlay 归属 shell 包。 */
  public static final String OVERLAY_PACKAGE = "com.android.shell:noMirror";

  private static final String OVERLAY_NAME = "noMirror";
  private static final String RESOURCE = "android:bool/config_localDisplaysMirrorContent";
  /** RRO 里 bool 的类型码,对应 AssetManager 的 TYPE_INT_BOOLEAN。 */
  private static final String TYPE_INT_BOOLEAN = "0x12";
  private static final String VALUE_FALSE = "0x0";

  static final String CMD_ENABLE =
      "cmd overlay fabricate --target android --name "
          + OVERLAY_NAME
          + " "
          + RESOURCE
          + " "
          + TYPE_INT_BOOLEAN
          + " "
          + VALUE_FALSE
          + "; cmd overlay enable --user 0 "
          + OVERLAY_PACKAGE;

  static final String CMD_DISABLE = "cmd overlay disable --user 0 " + OVERLAY_PACKAGE;

  static final String CMD_LIST = "cmd overlay list --user 0";

  public static void setEnabled(boolean enabled, ShellExecutor.Callback callback) {
    ShellExecutor.runAsync(enabled ? CMD_ENABLE : CMD_DISABLE, callback);
  }

  /** 查询 overlay 的真实启用状态;拿不到时回调 null。 */
  public static void queryEnabled(StatusCallback callback) {
    ShellExecutor.runAsync(
        CMD_LIST,
        (success, output) -> callback.onStatus(success ? parseEnabled(output) : null));
  }

  /**
   * 解析 {@code cmd overlay list} 的输出,返回 null 表示无法判断(没权限或格式不认识),
   * 这样调用方可以退回本地记录的状态。
   *
   * <p>输出里每个 overlay 是自己单占一行、前面带中括号的,中括号后面才是 overlay 包名,形如:
   *
   * <pre>
   *   android
   *   [X] com.android.shell:noMirror
   * </pre>
   *
   * 中括号内为空格表示「已安装未启用」,有字符(x / X)表示「已启用」;输出里单独的 {@code ---}
   * 表示该 overlay 已安装但存在错误,这种情况按未启用来处理不了,直接跳过。
   */
  static Boolean parseEnabled(String output) {
    if (output == null) {
      return null;
    }
    for (String rawLine : output.split("\n")) {
      String line = rawLine.trim();
      if (!line.startsWith("[")) {
        continue;
      }
      int close = line.indexOf(']');
      if (close < 0) {
        continue;
      }
      // 中括号后面跟的是 overlay 包名,可能再带空格和附加说明,取第一段比对
      String name = line.substring(close + 1).trim();
      if (name.isEmpty()) {
        continue;
      }
      if (!OVERLAY_PACKAGE.equals(name.split("\\s+")[0])) {
        continue;
      }
      // 中括号内为空 => 未启用;有内容(x / X)=> 已启用
      return !line.substring(1, close).trim().isEmpty();
    }
    return null;
  }

  public interface StatusCallback {
    /** enabled 为 null 表示状态未知。 */
    void onStatus(Boolean enabled);
  }
}
