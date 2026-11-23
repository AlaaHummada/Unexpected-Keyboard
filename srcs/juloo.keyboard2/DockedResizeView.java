package juloo.keyboard2;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Thin strip above the docked keyboard with an up-down grip in its top-left
    corner, used to change the keyboard height. Touches outside the grip are
    ignored. The touch handling is done in [Keyboard2]. */
public class DockedResizeView extends View
{
  private final Paint _paint;
  private final Path _path = new Path();

  public DockedResizeView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    _paint.setStyle(Paint.Style.STROKE);
    _paint.setStrokeCap(Paint.Cap.ROUND);
    _paint.setStrokeJoin(Paint.Join.ROUND);
    _paint.setStrokeWidth(dp(context, 2.5f));
    TypedArray a = context.getTheme().obtainStyledAttributes(R.styleable.keyboard);
    _paint.setColor(a.getColor(R.styleable.keyboard_colorLabel, 0));
    a.recycle();
    _paint.setAlpha(140);
  }

  /** Width of the touchable zone at the left. */
  public static float touchWidth(View v)
  {
    return dp(v.getContext(), 64);
  }

  @Override
  protected void onDraw(Canvas canvas)
  {
    Context c = getContext();
    float in = dp(c, 6);
    float s = dp(c, 12);
    float top = dp(c, 3);
    float bottom = getHeight() - dp(c, 2);
    _path.reset();
    // L-shaped corner
    _path.moveTo(in, bottom);
    _path.lineTo(in, top);
    _path.lineTo(in + s, top);
    // Up-down arrow next to it
    float ax = in + s + dp(c, 9);
    float ah = dp(c, 3);
    _path.moveTo(ax, top);
    _path.lineTo(ax, bottom);
    _path.moveTo(ax - ah, top + ah);
    _path.lineTo(ax, top);
    _path.lineTo(ax + ah, top + ah);
    _path.moveTo(ax - ah, bottom - ah);
    _path.lineTo(ax, bottom);
    _path.lineTo(ax + ah, bottom - ah);
    canvas.drawPath(_path, _paint);
  }

  static float dp(Context c, float v)
  {
    return v * c.getResources().getDisplayMetrics().density;
  }
}
