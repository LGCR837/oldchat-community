package com.im.oldchat.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.support.v4.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 经典 Cover Flow：左右封面内倾，拖拽实时过渡，松手自动吸附。
 * 支持动态加载，按需加载可见区域的封面图片。
 */
public class CoverFlowView extends View {

    public static class Item {
        public final String mId;
        public final String mTitle;
        public final String mSubtitle;
        public final String mCoverPath;
        public final Object mTag;

        public Item(String id, String title, String subtitle, String coverPath, Object tag) {
            mId = id;
            mTitle = title;
            mSubtitle = subtitle;
            mCoverPath = coverPath;
            mTag = tag;
        }
    }

    public interface OnItemClickListener {
        void onItemClick(int position, Item item);
    }

    public interface OnSelectionChangeListener {
        void onSelectionChanged(int position, Item item);
    }

    // 动态加载参数
    private static final int VISIBLE_RANGE = 6; // 当前位置左右各加载6个，确保能看到更多
    private static final int CACHE_SIZE = 12;   // Bitmap 缓存数量

    private static final float DEFAULT_MAX_ROTATION = 78f;
    private static final float DEFAULT_FAR_SCALE = 0.78f;
    private static final float DEFAULT_FAR_ALPHA = 0.15f;
    private static final float WATCH_MAX_ROTATION = 66f;
    private static final float WATCH_FAR_SCALE = 0.82f;
    private static final float WATCH_FAR_ALPHA = 0.24f;

    private final List<Item> mItems = new ArrayList<Item>();
    private final LruCache<String, Bitmap> mBitmapCache;
    private final Set<String> mPendingLoads = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> mFailedLoads = Collections.synchronizedSet(new HashSet<String>());
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera mCamera = new Camera();
    private final Matrix mMatrix = new Matrix();
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();
    private final GestureDetector mGestureDetector;

    private float mDensity;
    private int mCoverSize;
    private int mBasePhoneCoverSize;
    private float mCenterX;
    private float mCenterY;
    private float mInfoY;
    private float mDotY;
    private float mSpacing;
    private float mSideCompression = 1f;
    private float mMaxRotation = DEFAULT_MAX_ROTATION;
    private float mFarScale = DEFAULT_FAR_SCALE;
    private float mFarAlpha = DEFAULT_FAR_ALPHA;
    private float mCenterOffsetRatio = 0f;
    private float mDragStartX;
    private float mDragStartPosition;
    private float mScrollPosition;
    private boolean mDragging;
    private boolean mFlingHandled;
    // API<11 无 android.animation.ValueAnimator，用 Object 承载避免类加载崩溃（低版本直接跳转）
    private Object mAnimator;
    private OnItemClickListener mClickListener;
    private OnSelectionChangeListener mSelectionChangeListener;
    private int mLastNotifiedIndex = -1;
    private boolean mWatchMode = false;
    private Shader mBgLinearShader;
    private Shader mBgRadialShader;
    private Shader mBgBottomShader;

    public CoverFlowView(Context context) {
        this(context, null);
    }

    public CoverFlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGestureDetector = new GestureDetector(context, new GestureListener());
        // 初始化 Bitmap 缓存（按 KB 预算，最大不超过可用堆内存的 1/16，上限 8MB）
        int maxCacheKb = Math.max(2048, Math.min(8192, (int) (Runtime.getRuntime().maxMemory() / 1024 / 16)));
        mBitmapCache = new LruCache<String, Bitmap>(maxCacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                if (bitmap == null || bitmap.isRecycled()) {
                    return 0;
                }
                return (bitmap.getRowBytes() * bitmap.getHeight()) / 1024;
            }
        };
        init(context);
    }

    private void init(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mBasePhoneCoverSize = dp(190);
        mCoverSize = mBasePhoneCoverSize;
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(sp(20));
        mTextPaint.setFakeBoldText(true);
        mSubTextPaint.setColor(0xB8FFFFFF);
        mSubTextPaint.setTextAlign(Paint.Align.CENTER);
        mSubTextPaint.setTextSize(sp(13));
        mOverlayPaint.setColor(0x18000000);
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        setClickable(true);
    }

    public void setWatchMode(boolean watchMode) {
        if (mWatchMode == watchMode) {
            return;
        }
        mWatchMode = watchMode;
        if (watchMode) {
            mMaxRotation = WATCH_MAX_ROTATION;
            mFarScale = WATCH_FAR_SCALE;
            mFarAlpha = WATCH_FAR_ALPHA;
            mSideCompression = 0.58f;
            mCenterOffsetRatio = 0f;
        } else {
            // 手机版沿用手表版的层叠和倾斜手感，只保留更大的封面尺寸
            mMaxRotation = WATCH_MAX_ROTATION;
            mFarScale = WATCH_FAR_SCALE;
            mFarAlpha = WATCH_FAR_ALPHA;
            mSideCompression = 0.58f;
            mCenterOffsetRatio = 0f;
        }
        updateMetrics();
        mBitmapCache.evictAll();
        mPendingLoads.clear();
        mFailedLoads.clear();
        invalidate();
    }

    private void updateMetrics() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (mWatchMode) {
            int shortSide = Math.min(getWidth(), getHeight());
            int adaptiveSize = (int) (shortSide * 0.50f);
            mCoverSize = Math.max(dp(104), Math.min(adaptiveSize, dp(156)));
        } else {
            mCoverSize = mBasePhoneCoverSize;
        }
        mCenterX = getWidth() / 2f + getWidth() * mCenterOffsetRatio;
        mCenterY = getHeight() * (mWatchMode ? 0.32f : 0.35f);
        mInfoY = getHeight() * (mWatchMode ? 0.70f : 0.78f);
        mDotY = getHeight() - dp(mWatchMode ? 18 : 24);
        mSpacing = mCoverSize * (mWatchMode ? 0.68f : 0.52f);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        mClickListener = listener;
    }

    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        mSelectionChangeListener = listener;
    }

    /**
     * 设置数据，支持任意数量，采用动态加载机制
     * 优化：如果数据没有变化，不清空缓存避免闪烁
     */
    public void setItems(List<Item> items) {
        stopAnimation();

        // 检查数据是否真的变化了
        if (isSameItems(items)) {
            // 数据没变，只恢复位置
            mScrollPosition = clampPosition(mScrollPosition);
            invalidate();
            return;
        }

        // 数据变化了，清空缓存重新加载
        mBitmapCache.evictAll();
        mPendingLoads.clear();
        mFailedLoads.clear();
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        mScrollPosition = clampPosition(mScrollPosition);
        mLastNotifiedIndex = -1;
        notifySelectionChanged();
        invalidate();
    }

    /**
     * 检查新数据是否与当前数据相同（避免不必要的刷新）
     */
    private boolean isSameItems(List<Item> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return mItems.isEmpty();
        }
        if (newItems.size() != mItems.size()) {
            return false;
        }
        // 检查每个item的id是否相同
        for (int i = 0; i < newItems.size(); i++) {
            Item newItem = newItems.get(i);
            Item oldItem = mItems.get(i);
            if (newItem == null || oldItem == null) {
                return false;
            }
            if (!newItem.mId.equals(oldItem.mId)) {
                return false;
            }
        }
        return true;
    }

    public void setCurrentIndexSilently(int index) {
        mScrollPosition = clampPosition(index);
        mLastNotifiedIndex = -1;
        notifySelectionChanged();
        invalidate();
    }

    // 线程池用于后台加载封面
    private final java.util.concurrent.ExecutorService mExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);

    /**
     * 获取封面 Bitmap，优先从缓存加载
     */
    private Bitmap getCoverBitmap(Item item) {
        if (item == null || item.mCoverPath == null || item.mCoverPath.length() == 0) {
            return null;
        }
        // 先从缓存获取
        Bitmap cached = mBitmapCache.get(item.mCoverPath);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        if (mFailedLoads.contains(item.mCoverPath)) {
            return null;
        }
        enqueueBitmapLoad(item);
        return null;
    }

    private void enqueueBitmapLoad(Item item) {
        if (item == null || item.mCoverPath == null || item.mCoverPath.length() == 0) {
            return;
        }
        Bitmap cached = mBitmapCache.get(item.mCoverPath);
        if (cached != null && !cached.isRecycled()) {
            return;
        }
        if (mFailedLoads.contains(item.mCoverPath)) {
            return;
        }
        if (!mPendingLoads.add(item.mCoverPath)) {
            return;
        }
        mExecutor.execute(new LoadBitmapTask(item));
    }

    /**
     * 后台加载 Bitmap 任务
     */
    private class LoadBitmapTask implements Runnable {
        private final Item mItem;

        LoadBitmapTask(Item item) {
            mItem = item;
        }

        @Override
        public void run() {
            if (mItem == null || mItem.mCoverPath == null) {
                if (mItem != null) {
                    mPendingLoads.remove(mItem.mCoverPath);
                }
                return;
            }
            // 检查是否仍在缓存范围内
            if (!isInCacheRange(mItem)) {
                mPendingLoads.remove(mItem.mCoverPath);
                return;
            }
            // 压缩加载：遍历所有线路候选（主线路可能 404，自动回退备用线路）
            Bitmap bmp = null;
            String[] candidates = com.im.oldchat.util.MediaUrlResolver.resolveCandidates(mItem.mCoverPath);
            if (candidates != null) {
                for (String candidate : candidates) {
                    bmp = decodeSampledBitmap(candidate, mCoverSize, mCoverSize);
                    if (bmp != null) {
                        break;
                    }
                }
            }
            if (bmp == null) {
                mFailedLoads.add(mItem.mCoverPath);
                mPendingLoads.remove(mItem.mCoverPath);
                return;
            }
            mBitmapCache.put(mItem.mCoverPath, bmp);
            mFailedLoads.remove(mItem.mCoverPath);
            mPendingLoads.remove(mItem.mCoverPath);
            // 刷新显示（主线程）
            postInvalidate();
        }
    }
    
    private static final int MAX_COVER_DOWNLOAD_BYTES = 4 * 1024 * 1024; // 4MB 单图下载上限

    // 压缩加载 Bitmap（支持本地文件路径与 http/https 网络 URL）
    private Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        try {
            if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
                byte[] bytes = downloadBytes(path);
                if (bytes == null) {
                    return null;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;

                Bitmap bmp = null;
                try {
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                } catch (OutOfMemoryError oom) {
                    options.inSampleSize *= 2;
                    try {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    } catch (OutOfMemoryError ignored) {
                        return null;
                    }
                }
                if (bmp != null && (bmp.getWidth() != reqWidth || bmp.getHeight() != reqHeight)) {
                    try {
                        Bitmap scaled = Bitmap.createScaledBitmap(bmp, reqWidth, reqHeight, true);
                        if (scaled != bmp) {
                            bmp.recycle();
                        }
                        return scaled;
                    } catch (OutOfMemoryError oom) {
                        return bmp;
                    }
                }
                return bmp;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp = null;
            try {
                bmp = BitmapFactory.decodeFile(path, options);
            } catch (OutOfMemoryError oom) {
                options.inSampleSize *= 2;
                try {
                    bmp = BitmapFactory.decodeFile(path, options);
                } catch (OutOfMemoryError ignored) {
                    return null;
                }
            }
            if (bmp != null && (bmp.getWidth() != reqWidth || bmp.getHeight() != reqHeight)) {
                try {
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, reqWidth, reqHeight, true);
                    if (scaled != bmp) {
                        bmp.recycle();
                    }
                    return scaled;
                } catch (OutOfMemoryError oom) {
                    return bmp;
                }
            }
            return bmp;
        } catch (Throwable e) {
            return null;
        }
    }

    private byte[] downloadBytes(String url) {
        java.io.InputStream inputStream = null;
        java.io.ByteArrayOutputStream bos = null;
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_COVER_DOWNLOAD_BYTES) {
                return null;
            }
            inputStream = connection.getInputStream();
            bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            int total = 0;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (total > MAX_COVER_DOWNLOAD_BYTES) {
                    return null;
                }
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception ignored) {
                }
            }
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
    
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    /**
     * 判断 Item 是否在缓存范围内（即将可见或当前可见）
     */
    private boolean isInCacheRange(Item item) {
        int index = -1;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i) == item) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        float diff = Math.abs(index - mScrollPosition);
        return diff <= VISIBLE_RANGE + 1;
    }

    /**
     * 预加载即将可见的封面
     */
    private void preloadNearbyBitmaps() {
        int center = Math.round(mScrollPosition);
        int start = Math.max(0, center - VISIBLE_RANGE);
        int end = Math.min(mItems.size() - 1, center + VISIBLE_RANGE);
        for (int i = start; i <= end; i++) {
            Item item = mItems.get(i);
            if (item != null && item.mCoverPath != null) {
                Bitmap cached = mBitmapCache.get(item.mCoverPath);
                if (cached == null || cached.isRecycled()) {
                    enqueueBitmapLoad(item);
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMetrics();
        if (w > 0 && h > 0) {
            mBgLinearShader = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF171B2D, 0xFF102442, 0xFF0A1730},
                    new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP);
            mBgRadialShader = new RadialGradient(mCenterX, mCenterY, dp(230),
                    new int[]{0x407173FF, 0x127173FF, 0x00000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            mBgBottomShader = new LinearGradient(0, h - dp(150), 0, h,
                    new int[]{0x00000000, 0x46000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        if (mItems.isEmpty()) {
            return;
        }
        int centerIndex = Math.round(mScrollPosition);

        // 计算绘制范围（比可见范围稍大，确保滑动流畅）
        int extraRange = mWatchMode ? 0 : 1;
        int drawRange = mWatchMode ? Math.max(4, VISIBLE_RANGE - 1) : VISIBLE_RANGE;
        int drawStart = Math.max(0, centerIndex - drawRange - extraRange);
        int drawEnd = Math.min(mItems.size() - 1, centerIndex + drawRange + extraRange);

        // 按深度排序绘制（远的先画）
        // 使用列表存储需要绘制的项及其深度
        List<int[]> toDraw = new ArrayList<int[]>();
        for (int i = drawStart; i <= drawEnd; i++) {
            float diff = i - mScrollPosition;
            int depth = Math.round(Math.abs(diff));
            toDraw.add(new int[]{i, depth, (int)(diff * 100)});
        }
        // 按深度降序排序
        for (int i = 0; i < toDraw.size() - 1; i++) {
            for (int j = i + 1; j < toDraw.size(); j++) {
                if (toDraw.get(i)[1] < toDraw.get(j)[1]) {
                    int[] temp = toDraw.get(i);
                    toDraw.set(i, toDraw.get(j));
                    toDraw.set(j, temp);
                }
            }
        }
        // 绘制
        for (int i = 0; i < toDraw.size(); i++) {
            int[] item = toDraw.get(i);
            drawItem(canvas, item[0], item[2] / 100f);
        }
        drawInfo(canvas, centerIndex);
        drawDots(canvas, centerIndex);

        // 预加载附近的封面
        preloadNearbyBitmaps();
    }

    private void drawBackground(Canvas canvas) {
        if (mBgLinearShader != null) {
            mBgPaint.setShader(mBgLinearShader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mBgPaint);
            mBgPaint.setShader(mBgRadialShader);
            canvas.drawCircle(mCenterX, mCenterY, dp(230), mBgPaint);
            mBgPaint.setShader(mBgBottomShader);
            canvas.drawRect(0, getHeight() - dp(150), getWidth(), getHeight(), mBgPaint);
            mBgPaint.setShader(null);
        }
    }

    private void drawItem(Canvas canvas, int index, float diff) {
        float abs = Math.abs(diff);
        float normalized = Math.min(abs / 2.2f, 1f);
        float rotation = mMaxRotation * Math.min(abs / (mWatchMode ? 1.25f : 1.05f), 1f);
        float scale = 1f - (1f - mFarScale) * normalized;
        float alpha = 1f - (1f - mFarAlpha) * normalized;
        float sideFactor = 1f;
        if (abs > 0.01f) {
            float level = Math.min(abs, 3f) / 3f;
            sideFactor = 1f - (level * level) * (1f - mSideCompression);
        }
        float x = mCenterX + diff * mSpacing * sideFactor;
        if (!shouldDrawItem(x, scale)) {
            return;
        }
        rotation = diff < 0f ? rotation : -rotation;

        float half = mCoverSize / 2f;
        int save = canvas.save();
        mMatrix.reset();
        mCamera.save();
        if (android.os.Build.VERSION.SDK_INT >= 12) {
            mCamera.setLocation(0, 0, -10 * mDensity);
        }
        mCamera.rotateY(rotation);
        mCamera.getMatrix(mMatrix);
        mCamera.restore();
        mMatrix.preTranslate(-half, -half);
        mMatrix.postTranslate(x, mCenterY);
        mMatrix.postScale(scale, scale, x, mCenterY);
        canvas.concat(mMatrix);
        drawShadow(canvas, alpha);
        drawCover(canvas, index, alpha);
        canvas.restoreToCount(save);
    }

    private boolean shouldDrawItem(float centerX, float scale) {
        float half = (mCoverSize * scale) * 0.5f;
        float safeMargin = dp(mWatchMode ? 10 : 18);
        return centerX + half >= -safeMargin && centerX - half <= getWidth() + safeMargin;
    }

    private void drawShadow(Canvas canvas, float alpha) {
        mShadowPaint.setShadowLayer(dp(16), 0, dp(10), 0x66000000);
        mShadowPaint.setColor(0x99000000);
        mShadowPaint.setAlpha((int) (115 * alpha));
        canvas.drawRect(0, 0, mCoverSize, mCoverSize, mShadowPaint);
        mShadowPaint.clearShadowLayer();
    }

    private void drawCover(Canvas canvas, int index, float alpha) {
        Item item = mItems.get(index);
        Bitmap bitmap = getCoverBitmap(item);
        if (bitmap != null && !bitmap.isRecycled()) {
            mSrc.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
            mDst.set(0, 0, mCoverSize, mCoverSize);
            mPaint.setAlpha((int) (255 * alpha));
            canvas.drawBitmap(bitmap, mSrc, mDst, mPaint);
        } else {
            drawFallback(canvas, index, alpha, Math.abs(index - mScrollPosition) < 0.45f);
        }
        mOverlayPaint.setAlpha((int) (36 * alpha));
        canvas.drawRect(0, 0, mCoverSize, mCoverSize, mOverlayPaint);
        mBgPaint.setShader(new LinearGradient(0, 0, mCoverSize, 0,
                new int[]{0x2EFFFFFF, 0x00FFFFFF, 0x14FFFFFF},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        mBgPaint.setAlpha((int) (255 * alpha));
        canvas.drawRect(0, 0, mCoverSize, mCoverSize, mBgPaint);
        mBgPaint.setShader(null);
    }

    private void drawFallback(Canvas canvas, int index, float alpha, boolean showTitle) {
        Item item = mItems.get(index);
        int hash = Math.abs(safe(item.mTitle, "music").hashCode());
        int c1 = Color.rgb(55 + hash % 90, 70 + (hash / 9) % 90, 100 + (hash / 17) % 110);
        int c2 = Color.rgb(18 + hash % 38, 22 + (hash / 11) % 40, 36 + (hash / 15) % 55);
        mBgPaint.setShader(new LinearGradient(0, 0, mCoverSize, mCoverSize, c1, c2, Shader.TileMode.CLAMP));
        mBgPaint.setAlpha((int) (255 * alpha));
        canvas.drawRect(0, 0, mCoverSize, mCoverSize, mBgPaint);
        mBgPaint.setShader(null);
        if (showTitle) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(0xDFFFFFFF);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(sp(14));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float y = mCoverSize / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(shortText(item.mTitle, 10), mCoverSize / 2f, y, textPaint);
            return;
        }
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xECFFFFFF);
        canvas.drawCircle(mCoverSize / 2f, mCoverSize / 2f, mCoverSize * 0.11f, dotPaint);
    }

    private void drawInfo(Canvas canvas, int centerIndex) {
        if (centerIndex < 0 || centerIndex >= mItems.size()) {
            return;
        }
        Item item = mItems.get(centerIndex);
        canvas.drawText(shortText(safe(item.mTitle, "未命名"), 18), mCenterX, mInfoY, mTextPaint);
        String subtitle = item.mSubtitle;
        if (!TextUtils.isEmpty(subtitle)) {
            canvas.drawText(shortText(subtitle, 22), mCenterX, mInfoY + dp(24), mSubTextPaint);
        }
    }

    private void drawDots(Canvas canvas, int centerIndex) {
        if (mItems.size() <= 1) {
            return;
        }

        // 最多显示 20 个指示点，避免过多
        int maxDots = Math.min(mItems.size(), 20);
        int startIndex = 0;
        if (mItems.size() > 20) {
            // 居中显示当前选中的点
            startIndex = Math.max(0, Math.min(centerIndex - 10, mItems.size() - 20));
        }

        float spacing = dp(12);
        float totalWidth = (maxDots - 1) * spacing;
        float start = mCenterX - totalWidth / 2f;

        for (int i = 0; i < maxDots; i++) {
            int actualIndex = startIndex + i;
            float distance = Math.abs(actualIndex - mScrollPosition);
            float radius = dp(distance < 0.5f ? 3.5f : 2.3f);
            mDotPaint.setColor(actualIndex == centerIndex ? 0xFFFFFFFF : 0x55FFFFFF);
            canvas.drawCircle(start + i * spacing, mDotY, radius, mDotPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mItems.isEmpty()) {
            return super.onTouchEvent(event);
        }
        mGestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopAnimation();
                mDragging = true;
                mFlingHandled = false;
                mDragStartX = event.getX();
                mDragStartPosition = mScrollPosition;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    float delta = (mDragStartX - event.getX()) / mSpacing;
                    delta *= mWatchMode ? 0.78f : 0.88f;
                    mScrollPosition = clampPositionWithResistance(mDragStartPosition + delta);
                    notifySelectionChanged();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                float upX = event.getX();
                float upY = event.getY();
                boolean moved = Math.abs(upX - mDragStartX) > dp(8);
                if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    handleTap(upX, upY);
                }
                mDragging = false;
                if (!mFlingHandled) {
                    snapToNearest();
                }
                return true;
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        float half = mCoverSize / 2f;
        if (Math.abs(x - mCenterX) <= half && Math.abs(y - mCenterY) <= half) {
            Item item = getCurrentItem();
            int index = getCurrentIndex();
            if (item != null && mClickListener != null) {
                mClickListener.onItemClick(index, item);
            }
            return;
        }
        // 点击背景/两侧：不做任何翻页动画（用户要求去除点击下沉/翻页 3D 效果）
    }

    public void moveToPrevious() {
        animateTo(clampPosition(getCurrentIndex() - 1));
    }

    public void moveToNext() {
        animateTo(clampPosition(getCurrentIndex() + 1));
    }

    public int getCurrentIndex() {
        return Math.round(mScrollPosition);
    }

    public Item getCurrentItem() {
        int index = getCurrentIndex();
        if (index >= 0 && index < mItems.size()) {
            return mItems.get(index);
        }
        return null;
    }

    private void notifySelectionChanged() {
        int index = getCurrentIndex();
        if (index == mLastNotifiedIndex) {
            return;
        }
        mLastNotifiedIndex = index;
        if (mSelectionChangeListener != null) {
            mSelectionChangeListener.onSelectionChanged(index, getCurrentItem());
        }
    }

    private void snapToNearest() {
        float floor = (float) Math.floor(mScrollPosition);
        float center = floor + 0.5f;
        float target;
        float stickyRange = mWatchMode ? 0.34f : 0.30f;

        if (Math.abs(mScrollPosition - center) <= stickyRange) {
            target = Math.round(mScrollPosition);
        } else {
            target = mScrollPosition < center ? floor : (float) Math.ceil(mScrollPosition);
        }
        animateTo(clampPosition(target));
    }

    private void animateTo(float target) {
        stopAnimation();
        if (android.os.Build.VERSION.SDK_INT < 11) {
            mScrollPosition = clampPosition(target);
            notifySelectionChanged();
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mScrollPosition, target);
        animator.setDuration(mWatchMode ? 180 : 220);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mScrollPosition = ((Float) animation.getAnimatedValue()).floatValue();
                notifySelectionChanged();
                invalidate();
            }
        });
        mAnimator = animator;
        animator.start();
    }

    private void stopAnimation() {
        if (mAnimator != null) {
            if (android.os.Build.VERSION.SDK_INT >= 11) {
                ((ValueAnimator) mAnimator).cancel();
            }
            mAnimator = null;
        }
    }

    private void flingTo(float velocityX) {
        float minVelocity = dp(mWatchMode ? 110 : 200);
        float maxVelocity = dp(mWatchMode ? 2200 : 2600);
        
        if (Math.abs(velocityX) < minVelocity) {
            snapToNearest();
            return;
        }
        
        float velocityRatio = Math.min(Math.abs(velocityX), maxVelocity) / maxVelocity;
        float maxTravelDistance = Math.min(mItems.size() - 1, mWatchMode ? 3.2f : 4.5f);
        float travelDistance = velocityRatio * maxTravelDistance;
        if (travelDistance < (mWatchMode ? 1.05f : 0.75f)) {
            travelDistance = mWatchMode ? 1.05f : 0.75f;
        }
        
        if (velocityX > 0) {
            // 向右滑，位置减小（显示左边的封面）
            startFlingAnimation(mScrollPosition, clampPosition(mScrollPosition - travelDistance));
        } else {
            // 向左滑，位置增大（显示右边的封面）
            startFlingAnimation(mScrollPosition, clampPosition(mScrollPosition + travelDistance));
        }
    }
    
    /**
     * 带惯性的滑动动画，带减速效果
     */
    private void startFlingAnimation(float fromPosition, float toPosition) {
        stopAnimation();

        float distance = toPosition - fromPosition;
        float duration = (mWatchMode ? 240 : 260) + Math.abs(distance) * (mWatchMode ? 120 : 85);
        duration = Math.min(duration, mWatchMode ? 560 : 700);

        if (android.os.Build.VERSION.SDK_INT < 11) {
            mScrollPosition = clampPosition(toPosition);
            notifySelectionChanged();
            invalidate();
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(fromPosition, toPosition);
        animator.setDuration((long) duration);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(mWatchMode ? 1.8f : 1.5f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mScrollPosition = ((Float) animation.getAnimatedValue()).floatValue();
                notifySelectionChanged();
                invalidate();
            }
        });
        animator.addListener(new android.animation.Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {}
            
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 动画结束后吸附到最近位置
                snapToNearest();
            }
            
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {}
            
            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {}
        });
        mAnimator = animator;
        animator.start();
    }

    private float clampPosition(float value) {
        if (mItems.isEmpty()) {
            return 0f;
        }
        if (value < 0f) {
            return 0f;
        }
        float max = mItems.size() - 1;
        return value > max ? max : value;
    }

    private float clampPositionWithResistance(float value) {
        if (mItems.isEmpty()) {
            return 0f;
        }
        float max = mItems.size() - 1;
        if (value < 0f) {
            return value * 0.28f;
        }
        if (value > max) {
            return max + (value - max) * 0.28f;
        }
        return value;
    }

    private int dp(float value) {
        return (int) (value * mDensity + 0.5f);
    }

    private int sp(float value) {
        return (int) (value * getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }

    private String safe(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private String shortText(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        // 清空缓存避免内存泄漏
        if (mBitmapCache != null) {
            mBitmapCache.evictAll();
        }
        mPendingLoads.clear();
        mFailedLoads.clear();
        // 关闭线程池
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdown();
        }
        super.onDetachedFromWindow();
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (Math.abs(velocityX) < dp(140)) {
                return false;
            }
            mDragging = false;
            mFlingHandled = true;
            flingTo(velocityX);
            return true;
        }
    }
}
