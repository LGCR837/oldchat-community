package com.oldchat.fx.view;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.function.Consumer;

/** 复用真实 drawable PNG 的图标工具 + 头像/在线点/矢量加号。 */
public final class Widgets {

    private Widgets() {}

    /** 从 fx 资源加载真实 drawable PNG（ic_ 与 news_ 等图标）。 */
    public static ImageView iconImage(String name, double size) {
        ImageView iv = new ImageView();
        try {
            var url = Widgets.class.getResource("/com/oldchat/fx/drawable/" + name + ".png");
            if (url != null) iv.setImage(new Image(url.toExternalForm()));
        } catch (Exception ignored) {
        }
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        return iv;
    }

    /** 时间格式化（仿 Android 会话时间：今天 HH:mm / 昨天 / 周几 / MM/dd）。 */
    public static String formatTime(long time) {
        if (time <= 0) return "";
        long now = System.currentTimeMillis();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        String hm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        long diff = Math.max(0, now - time);
        long day = 24L * 3600 * 1000;
        if (diff < day && sameDay(c, now)) return hm;
        if (diff < 2 * day && yesterday(c, now)) return "昨天";
        if (diff < 7 * day) return weekday(c);
        return String.format("%02d/%02d", c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static boolean sameDay(java.util.Calendar c, long now) {
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.setTimeInMillis(now);
        return c.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
                && c.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR);
    }
    private static boolean yesterday(java.util.Calendar c, long now) {
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.setTimeInMillis(now - 24L * 3600 * 1000);
        return c.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
                && c.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR);
    }
    private static String weekday(java.util.Calendar c) {
        return new String[]{"周日", "周一", "周二", "周三", "周四", "周五", "周六"}[c.get(java.util.Calendar.DAY_OF_WEEK) - 1];
    }

    /** 矢量加号（替代缺失的 icon_chat_plus vector）。 */
    public static StackPane plusIcon(double size) {
        StackPane p = new StackPane();
        p.setPrefSize(size, size);
        double t = Math.max(2, size * 0.13);
        double bar = size * 0.6;
        Rectangle h = new Rectangle(bar, t, Color.web("#111827"));
        Rectangle v = new Rectangle(t, bar, Color.web("#111827"));
        h.setArcHeight(t); h.setArcWidth(t);
        v.setArcHeight(t); v.setArcWidth(t);
        p.getChildren().addAll(h, v);
        return p;
    }

    /** 方头像（会话列表，cornerRadius=0dp 的 RoundedImageView）。 */
    public static StackPane avatarSquare(String initial, String color, double size) {
        return avatar(initial, color, size, 0);
    }

    /** 圆头像（聊天头 CircleImageView）。 */
    public static StackPane avatar(String initial, String color, double size) {
        return avatar(initial, color, size, size / 2);
    }

    private static StackPane avatar(String initial, String color, double size, double radius) {
        StackPane p = new StackPane();
        p.setPrefSize(size, size);
        Region bg = new Region();
        bg.setPrefSize(size, size);
        bg.setStyle("-fx-background-color:" + (color == null ? "#9CA3AF" : color)
                + ";-fx-background-radius:" + radius + ";");
        Text t = new Text(initial == null || initial.isEmpty() ? "?" : initial.substring(0, 1));
        t.setFill(Color.WHITE);
        t.setFont(Font.font(14));
        p.getChildren().addAll(bg, t);
        return p;
    }

    /** 在线绿点（item_recent_chat 的 vRecentOnlineDot 10dp）。 */
    public static StackPane onlineDot(double size) {
        StackPane dot = new StackPane();
        dot.setPrefSize(size, size);
        Region r = new Region();
        r.setPrefSize(size, size);
        r.setStyle("-fx-background-color:#22C55E;-fx-background-radius:" + size / 2 + ";"
                + "-fx-border-color:#FFFFFF;-fx-border-width:2;");
        dot.getChildren().add(r);
        return dot;
    }

    /** 文字/字形图标按钮（返回箭头等）。 */
    public static StackPane iconButton(String glyph, double size, Runnable onClick) {
        StackPane p = new StackPane();
        p.setPrefSize(size, size);
        p.setCursor(Cursor.HAND);
        Text t = new Text(glyph);
        t.setFill(Color.web("#111827"));
        t.setFont(Font.font(16));
        p.getChildren().add(t);
        if (onClick != null) p.setOnMouseClicked(e -> onClick.run());
        return p;
    }

    /** 底部 tab 项：真实 PNG 图标 + 标签。选中态换 selected png + 主色文字。 */
    public static StackPane tabItem(String normalName, String selectedName, String label,
                                    boolean selected, Runnable onClick) {
        StackPane p = new StackPane();
        p.setCursor(Cursor.HAND);
        p.setPrefWidth(0); // 由父 HBox 均分
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(2);
        box.setAlignment(Pos.CENTER);
        ImageView iv = iconImage(selected ? selectedName : normalName, 24);
        javafx.scene.control.Label lb = new javafx.scene.control.Label(label);
        lb.setFont(Font.font(11));
        lb.setTextFill(Color.web(selected ? "#111827" : "#6B7280"));
        box.getChildren().addAll(iv, lb);
        p.getChildren().add(box);
        if (onClick != null) p.setOnMouseClicked(e -> onClick.run());
        return p;
    }
}
