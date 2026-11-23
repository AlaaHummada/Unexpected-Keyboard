package juloo.keyboard2;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Bar at the bottom of the floating keyboard with a resize grip in each
    corner. The touch handling is done in [Keyboard2]. */
public class FloatingResizeView extends View
{
  private static final float GRIP_SIZE_DP = 14;
  private static final float GRIP_THICKNESS_DP = 3;
  private static final float GRIP_INSET_DP = 6;
  private static final int GRIP_ALPHA = 140;

  private final Paint _paint;
  private final Path _path = new Path();

  public FloatingResizeView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    _paint.setStyle(Paint.Style.STROKE);
    _paint.setStrokeCap(Paint.Cap.ROUND);
    _paint.setStrokeJoin(Paint.Join.ROUND);
    _paint.setStrokeWidth(dp(GRIP_THICKNESS_DP));
    TypedArray a = context.getTheme().obtainStyledAttributes(R.styleable.keyboard);
    _paint.setColor(a.getColor(R.styleable.keyboard_colorLabel, 0));
    a.recycle();
    _paint.setAlpha(GRIP_ALPHA);
  }

  @Override
  protected void onDraw(Canvas canvas)
  {
    float s = dp(GRIP_SIZE_DP);
    float inset = dp(GRIP_INSET_DP);
    float bottom = getHeight() - inset;
    float right = getWidth() - inset;
    _path.reset();
    // Bottom-left corner
    _path.moveTo(inset, bottom - s);
    _path.lineTo(inset, bottom);
    _path.lineTo(inset + s, bottom);
    // Bottom-right corner
    _path.moveTo(right - s, bottom);
    _path.lineTo(right, bottom);
    _path.lineTo(right, bottom - s);
    canvas.drawPath(_path, _paint);
  }

  private float dp(float value)
  {
    return value * getResources().getDisplayMetrics().density;
  }
}
