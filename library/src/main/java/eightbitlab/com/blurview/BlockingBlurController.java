package eightbitlab.com.blurview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Arrays;

/**
 * Blur Controller that handles all blur logic for the attached View.
 * It honors View size changes, View animation and Visibility changes.
 * <p>
 * The basic idea is to draw the view hierarchy on a bitmap, excluding the attached View,
 * then blur and draw it on the system Canvas.
 * <p>
 * It uses {@link ViewTreeObserver.OnPreDrawListener} to detect when
 * blur should be updated.
 * <p>
 * Blur is done on the main thread.
 */
final class BlockingBlurController implements BlurController {
    
    private final boolean ENABLE_DEBUG = false;
    private final String TAG = "BlockingBlurController";

    /**
     * Bitmap size should be divisible by 16 to meet stride requirement
     *
     * Bitmap的size应该为16的倍数(不知道16是不是有什么说法,会不会是renderScript对于16倍数的图片会更快)
     */
    private static final int ROUNDING_VALUE = 16;
    @ColorInt
    static final int TRANSPARENT = 0;

    /**
     * 下采样系数
     *
     * 图片会根据这个值进行scale
     */
    private final float scaleFactor = DEFAULT_SCALE_FACTOR;
    private float blurRadius = DEFAULT_BLUR_RADIUS;
    //private float roundingWidthScaleFactor = 1f;
    //private float roundingHeightScaleFactor = 1f;
    private float bitmapScaleWidth = 1f;
    private float bitmapScaleHeight = 1f;

    private BlurAlgorithm blurAlgorithm;
    /**
     * internalCanvas仅仅是本类内部使用的canvas，
     * 它的作用是给internalBitmap服务
     *
     * 1.利用internalBitmap作为参数创建internalCanvas,这样利用internalCanvas上绘制的内容就可以绘制在internalBitmap上.
     *      to see {@link this#init(int, int)}
     * 2.利用internalCanvas作为参数(rootView.draw(internalCanvas))，将rootView上的内容绘制在internalBitmap.
     *      to see {@link this#updateBlur()}
     * 3.利用internalCanvas的translate scale，将rootView的内容进行translate和scale，使得internalBitmap的内容是rootView的对应位置，是rootView的对应大小.
     *      to see {@link this#setupInternalCanvasMatrix()}
     */
    private Canvas internalCanvas;
    /**
     * 关键点就是这个internalBitmap，
     * 它既被internalCanvas拿着，也被BlurView里重写的draw(Canvas canvas)里的canvas拿着
     *
     * 当internalBitmap的内容通过internalCanvas操作被更改时，canvas里也拿着这个bitmap的引用，所以同样能够绘制到屏幕上
     */
    private Bitmap internalBitmap;

    @SuppressWarnings("WeakerAccess")
    final View blurView;
    /**
     * 在demo中，它是一个透明的白色（#78FFFFFF），这个color作用于blur之后的图
     * 作者加这个color的目的应该是：当rootView是一个包含了blurView时，防止穿帮；加了一个透明的白色时，穿帮看不出来
     */
    private int overlayColor;
    /**
     * 需要blur的对象，它是一个ViewGroup.
     *
     * 注意：这个rootView从逻辑上讲不应该是一个包含blurView child的View，
     * 如果是blurView的父View，则blur的时候会将BlurView下面的绘制的内容一起blur进去。
     */
    private final ViewGroup rootView;
    /**
     * 为true表示'blurView对应的blur区域' 对应 '固定的位置和scale'
     * 为false表示'blurView对应的blur区域' 是一直在变化，即blurView自身的translate或scale是不断得在变化
     */
    private boolean hasFixedTransformationMatrix;

    private final ViewTreeObserver.OnPreDrawListener drawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            // Not invalidating a View here, just updating the Bitmap.
            // This relies on the HW accelerated bitmap drawing behavior in Android
            // If the bitmap was drawn on HW accelerated canvas, it holds a reference to it and on next
            // drawing pass the updated content of the bitmap will be rendered on the screen
            //
            // 不要在这儿 invalidate a view，只更新bitmap。
            // 它依赖于Android里硬件加速时，bitmap的绘制行为。
            // 如果bitmap是硬件加速绘制的，而且我们拿着internalBitmap的引用，
            // 在下一个draw pass时，会将更新过内容的bitmap渲染到屏幕上。

            long start;
            if (ENABLE_DEBUG) start = System.currentTimeMillis();
            updateBlur();
            if (ENABLE_DEBUG) System.out.println(TAG + " updateBlur onPreDraw time = " + (System.currentTimeMillis() - start));
            return true;
        }
    };

    private boolean blurEnabled = false;

    /**
     * 背景drawable
     */
    @Nullable
    private Drawable frameClearDrawable;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     * @param blurView View which will draw it's blurred underlying content
     * @param rootView Root View where blurView's underlying content starts drawing.
     *                 Can be Activity's root content layout (android.R.id.content)
     *                 or some of your custom root layouts.
     */
    BlockingBlurController(@NonNull View blurView, @NonNull ViewGroup rootView, @ColorInt int overlayColor) {
        this.rootView = rootView;
        this.blurView = blurView;
        this.overlayColor = overlayColor;
        this.blurAlgorithm = new NoOpBlurAlgorithm();

        int measuredWidth = blurView.getMeasuredWidth();
        int measuredHeight = blurView.getMeasuredHeight();

        if (isZeroSized(measuredWidth, measuredHeight)) {
            deferBitmapCreation();
            return;
        }

        init(measuredWidth, measuredHeight);
    }

    private int downScaleSize(float value) {
        return (int) Math.ceil(value / scaleFactor);
    }

    /**
     * Rounds a value to the nearest divisible by {@link #ROUNDING_VALUE} to meet stride requirement
     *
     * if value is 17, 17 - 17 % 16 + 16 = 32
     * if value is 33, 33 - 33 % 16 + 16 = 48
     */
    private int roundSize(int value) {
        if (value % ROUNDING_VALUE == 0) {
            return value;
        }
        return value - (value % ROUNDING_VALUE) + ROUNDING_VALUE;
    }

    @SuppressWarnings("WeakerAccess")
    void init(int measuredWidth, int measuredHeight) {
        if (isZeroSized(measuredWidth, measuredHeight)) {
            blurEnabled = false;
            blurView.setWillNotDraw(true);
            setBlurAutoUpdateInternal(false);
            return;
        }

        blurEnabled = true;
        blurView.setWillNotDraw(false);
        {
            if (ENABLE_DEBUG) System.out.println(TAG + " measuredWidth = " + measuredWidth + " measuredHeight = " + measuredHeight);
            // 图片的宽高先进行下采样
            int nonRoundedScaledWidth = downScaleSize(measuredWidth);
            int nonRoundedScaledHeight = downScaleSize(measuredHeight);
            if (ENABLE_DEBUG) System.out.println(TAG + " nonRoundedScaledWidth = " + nonRoundedScaledWidth + " nonRoundedScaledHeight = " + nonRoundedScaledHeight);

            // 再将宽高更改为16的倍数("round 16倍"操作)
            int scaledWidth = roundSize(nonRoundedScaledWidth);
            int scaledHeight = roundSize(nonRoundedScaledHeight);
            if (ENABLE_DEBUG) System.out.println(TAG + " scaledWidth = " + scaledWidth + " scaledHeight = " + scaledHeight);

            // 计算出图片进行"round 16倍"操作时，被scale了多少
            //roundingHeightScaleFactor = (float) nonRoundedScaledHeight / scaledHeight;
            //roundingWidthScaleFactor = (float) nonRoundedScaledWidth / scaledWidth;
            //if (ENABLE_DEBUG) System.out.println(TAG + " roundingWidthScaleFactor = " + roundingWidthScaleFactor + " roundingHeightScaleFactor = " + roundingHeightScaleFactor);
            //if (ENABLE_DEBUG) System.out.println(TAG + " allScale = " + roundingWidthScaleFactor * scaleFactor + " " + roundingHeightScaleFactor * scaleFactor);

            bitmapScaleWidth = (float) measuredWidth / scaledWidth;
            bitmapScaleHeight = (float) measuredHeight / scaledHeight;
            if (ENABLE_DEBUG) System.out.println(TAG + " bitmapScaleWidth = " + bitmapScaleWidth + " bitmapScaleHeight = " + bitmapScaleHeight);

            internalBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, blurAlgorithm.getSupportedBitmapConfig());
        }
        internalCanvas = new Canvas(internalBitmap);
        setBlurAutoUpdateInternal(true);
        if (hasFixedTransformationMatrix) {
            setupInternalCanvasMatrix();
        }
    }

    private boolean isZeroSized(int measuredWidth, int measuredHeight) {
        return downScaleSize(measuredHeight) == 0 || downScaleSize(measuredWidth) == 0;
    }

    /**
     * 调用这个方法有两个地方，一个是draw方法，一个是ViewTreeObserver.OnPreDrawListener
     *
     * 调用顺序为：程序一开始会调用draw方法，可能会调用1-2次，之后就再也不调用draw方法了，
     * 之后就是每帧都调用onPreDraw方法了。
     *
     * 第一次调用draw方法，就已经把internalBitmap引用传给draw方法里的canvas，
     * 之后在onPreDraw里更新这个bitmap引用里的内容就行了，减少了bitmap的copy，实现了比较快速的更新
     */
    @SuppressWarnings("WeakerAccess")
    void updateBlur() {
        if (!blurEnabled) {
            return;
        }

        if (frameClearDrawable == null) {
            internalBitmap.eraseColor(Color.TRANSPARENT);
        } else {
            frameClearDrawable.draw(internalCanvas);
        }

        if (hasFixedTransformationMatrix) {
            rootView.draw(internalCanvas);
        } else {
            internalCanvas.save();
            setupInternalCanvasMatrix();
            rootView.draw(internalCanvas);
            internalCanvas.restore();
        }

        blurAndSave();
    }

    /**
     * Deferring initialization until view is laid out
     */
    private void deferBitmapCreation() {
        blurView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    blurView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    legacyRemoveOnGlobalLayoutListener();
                }

                int measuredWidth = blurView.getMeasuredWidth();
                int measuredHeight = blurView.getMeasuredHeight();

                init(measuredWidth, measuredHeight);
            }

            @SuppressWarnings("deprecation")
            void legacyRemoveOnGlobalLayoutListener() {
                blurView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            }
        });
    }

    /**
     * root在屏幕上的位置
     */
    private final int[] rootLocation = new int[2];
    /**
     * blurView在屏幕上的位置
     */
    private final int[] blurViewLocation = new int[2];

    /**
     * Set up matrix to draw starting from blurView's position
     */
    private void setupInternalCanvasMatrix() {
        rootView.getLocationOnScreen(rootLocation);
        blurView.getLocationOnScreen(blurViewLocation);
        if (ENABLE_DEBUG) System.out.println(TAG + " rootLocation = " + Arrays.toString(rootLocation));
        if (ENABLE_DEBUG) System.out.println(TAG + " blurViewLocation = " + Arrays.toString(blurViewLocation));

        //int childCount = rootView.getChildCount();
        //int[] loc = new int[2];
        //for (int i = 0; i < childCount; i++) {
        //    View childAt = rootView.getChildAt(i);
        //    childAt.getLocationOnScreen(loc);
        //    if (ENABLE_DEBUG) System.out.println(TAG + " i = " + i + " loc = " + Arrays.toString(loc));
        //}

        int left = blurViewLocation[0] - rootLocation[0];
        int top = blurViewLocation[1] - rootLocation[1];
        if (ENABLE_DEBUG) System.out.println(TAG + " left = " + left + " top = " + top);

        //float scaleFactorX = scaleFactor * roundingWidthScaleFactor;
        //float scaleFactorY = scaleFactor * roundingHeightScaleFactor;
        float scaleFactorX = bitmapScaleWidth;
        float scaleFactorY = bitmapScaleHeight;
        if (ENABLE_DEBUG) System.out.println(TAG + " scaleFactorX = " + scaleFactorX + " scaleFactorY = " + scaleFactorY);

        float scaledLeftPosition = -left / scaleFactorX;
        float scaledTopPosition = -top / scaleFactorY;
        if (ENABLE_DEBUG) System.out.println(TAG + " scaledLeftPosition = " + scaledLeftPosition + " scaledTopPosition = " + scaledTopPosition);

        // 这里的translate和scale，是作用于rootView的。
        // 当调用rootView.draw(internalCanvas), rootView的内容会绘制在internalCanvas对应的internalBitmap上。
        // 注意这里要先translate再scale.
        // 若scaleFactorX为8，则1/8是比1小的数，即rootView绘制内容将被缩小
        internalCanvas.translate(scaledLeftPosition, scaledTopPosition);
        internalCanvas.scale(1 / scaleFactorX, 1 / scaleFactorY);
        if (ENABLE_DEBUG) System.out.println(TAG + " translate = " + scaledLeftPosition + " " + scaledTopPosition);
        if (ENABLE_DEBUG) System.out.println(TAG + " scale = " + scaleFactorX + " " + scaleFactorY);
    }

    @Override
    public void draw(Canvas canvas) {
        //draw only on system's hardware accelerated canvas
        if (!blurEnabled || !canvas.isHardwareAccelerated()) {
            return;
        }

        long start;
        if (ENABLE_DEBUG) start = System.currentTimeMillis();
        updateBlur();
        if (ENABLE_DEBUG) System.out.println(TAG + " updateBlur draw time = " + (System.currentTimeMillis() - start));

        canvas.save();
        //canvas.scale(scaleFactor * roundingWidthScaleFactor, scaleFactor * roundingHeightScaleFactor);
        canvas.scale(bitmapScaleWidth, bitmapScaleHeight);
        canvas.drawBitmap(internalBitmap, 0, 0, paint); // 这里将internalBitmap的引用给canvas
        canvas.restore();

        if (overlayColor != TRANSPARENT) {
            canvas.drawColor(overlayColor);
        }
    }

    private void blurAndSave() {
        // 这里，如果调用blur函数后，原来的internalBitmap被recycle了，返回了一个模糊过的internalBitmap，但返回的是一个新的bitmap引用
        // 则后面必须要调用internalCanvas.setBitmap(internalBitmap)，将新的bitmap引用，绘制在与internalCanvas绑定的internalBitmap上
        //
        // 但，这里blur函数，并不会将原来的internalBitmap recycle，它是直接在原引用上更改的，
        // 所以调用前后，internalBitmap还是那个bitmap
        // 则后面的internalCanvas.setBitmap(internalBitmap)可有可无，我试过了。
        if (ENABLE_DEBUG) System.out.println(TAG + " internalBitmap 1 = " + internalBitmap.hashCode() + " " + internalBitmap);
        internalBitmap = blurAlgorithm.blur(internalBitmap, blurRadius);
        if (ENABLE_DEBUG) System.out.println(TAG + " internalBitmap 2 = " + internalBitmap.hashCode() + " " + internalBitmap);
        if (!blurAlgorithm.canModifyBitmap()) {
            internalCanvas.setBitmap(internalBitmap);
        }
    }

    @Override
    public void updateBlurViewSize() {
        int measuredWidth = blurView.getMeasuredWidth();
        int measuredHeight = blurView.getMeasuredHeight();

        init(measuredWidth, measuredHeight);
    }

    @Override
    public void destroy() {
        setBlurAutoUpdateInternal(false);
        blurAlgorithm.destroy();
        if (internalBitmap != null) {
            internalBitmap.recycle();
        }
    }

    @Override
    public BlurViewFacade setBlurRadius(float radius) {
        this.blurRadius = radius;
        return this;
    }

    @Override
    public BlurViewFacade setBlurAlgorithm(BlurAlgorithm algorithm) {
        this.blurAlgorithm = algorithm;
        return this;
    }

    @Override
    public BlurViewFacade setFrameClearDrawable(@Nullable Drawable frameClearDrawable) {
        this.frameClearDrawable = frameClearDrawable;
        return this;
    }

    @SuppressWarnings("WeakerAccess")
    void setBlurEnabledInternal(boolean enabled) {
        this.blurEnabled = enabled;
        setBlurAutoUpdateInternal(enabled);
        blurView.invalidate();
    }

    @Override
    public BlurViewFacade setBlurEnabled(final boolean enabled) {
        blurView.post(new Runnable() {
            @Override
            public void run() {
                setBlurEnabledInternal(enabled);
            }
        });
        return this;
    }

    @SuppressWarnings("WeakerAccess")
    void setBlurAutoUpdateInternal(boolean enabled) {
        blurView.getViewTreeObserver().removeOnPreDrawListener(drawListener);
        if (enabled) {
            blurView.getViewTreeObserver().addOnPreDrawListener(drawListener);
        }
    }

    public BlurViewFacade setBlurAutoUpdate(final boolean enabled) {
        blurView.post(new Runnable() {
            @Override
            public void run() {
                setBlurAutoUpdateInternal(enabled);
            }
        });
        return this;
    }

    @Override
    public BlurViewFacade setHasFixedTransformationMatrix(boolean hasFixedTransformationMatrix) {
        this.hasFixedTransformationMatrix = hasFixedTransformationMatrix;
        return this;
    }

    @Override
    public BlurViewFacade setOverlayColor(int overlayColor) {
        if (this.overlayColor != overlayColor) {
            this.overlayColor = overlayColor;
            blurView.invalidate();
        }
        return this;
    }
}
