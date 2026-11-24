package juloo.keyboard2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Background picture and key sounds chosen by the user. They are copied
    into the app's device-protected storage so that the keyboard can use them
    even before the device is unlocked. */
public final class CustomAssets
{
  public static final String BACKGROUND = "keyboard_background.jpg";
  public static final String SOUND_KEY = "sound_key";
  public static final String SOUND_SPECIAL = "sound_special";
  /** Larger sound files are refused: they would be slow to play. */
  public static final long MAX_SOUND_BYTES = 2 * 1024 * 1024;

  public static File file(Context ctx, String name)
  {
    Context c = (VERSION.SDK_INT >= 24) ? ctx.createDeviceProtectedStorageContext() : ctx;
    return new File(c.getFilesDir(), name);
  }

  /** Decode, downscale and store the picture. Returns [false] on failure. */
  public static boolean import_image(Context ctx, Uri uri)
  {
    try
    {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      InputStream in = ctx.getContentResolver().openInputStream(uri);
      BitmapFactory.decodeStream(in, null, o);
      in.close();
      int sample = 1;
      while (Math.max(o.outWidth, o.outHeight) / sample > 2048)
        sample *= 2;
      o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      in = ctx.getContentResolver().openInputStream(uri);
      Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
      in.close();
      if (bmp == null)
        return false;
      OutputStream out = new FileOutputStream(file(ctx, BACKGROUND));
      bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
      out.close();
      bmp.recycle();
      return true;
    }
    catch (Exception e) { return false; }
  }

  /** Copy a sound file. Returns [false] on failure or if it's too large. */
  public static boolean import_sound(Context ctx, Uri uri, String name)
  {
    File dst = file(ctx, name);
    try
    {
      InputStream in = ctx.getContentResolver().openInputStream(uri);
      OutputStream out = new FileOutputStream(dst);
      byte[] buf = new byte[16384];
      long total = 0;
      int n;
      while ((n = in.read(buf)) > 0)
      {
        total += n;
        if (total > MAX_SOUND_BYTES)
        {
          in.close(); out.close(); dst.delete();
          return false;
        }
        out.write(buf, 0, n);
      }
      in.close();
      out.close();
      return true;
    }
    catch (Exception e) { dst.delete(); return false; }
  }

  static Bitmap _bg_cache = null;
  static long _bg_cache_stamp = -1;

  /** The background picture, or [null] if none is set. Cached. */
  public static Bitmap background(Context ctx)
  {
    File f = file(ctx, BACKGROUND);
    if (!f.exists())
    {
      _bg_cache = null;
      _bg_cache_stamp = -1;
      return null;
    }
    long stamp = f.lastModified() ^ f.length();
    if (_bg_cache == null || stamp != _bg_cache_stamp)
    {
      _bg_cache = BitmapFactory.decodeFile(f.getPath());
      _bg_cache_stamp = stamp;
    }
    return _bg_cache;
  }

  /** Draws a picture scaled to cover its bounds ("center crop"), darkened by
      [dim] (0 - 255). */
  public static final class CoverDrawable extends Drawable
  {
    final Bitmap _bmp;
    final Paint _paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint _dim = new Paint();
    final Matrix _matrix = new Matrix();

    public CoverDrawable(Bitmap bmp, int dim)
    {
      _bmp = bmp;
      _dim.setColor(0xFF000000);
      _dim.setAlpha(dim);
    }

    @Override
    public void draw(Canvas canvas)
    {
      android.graphics.Rect b = getBounds();
      if (b.width() <= 0 || b.height() <= 0)
        return;
      float s = Math.max((float)b.width() / _bmp.getWidth(),
          (float)b.height() / _bmp.getHeight());
      _matrix.reset();
      _matrix.setScale(s, s);
      _matrix.postTranslate(b.left + (b.width() - _bmp.getWidth() * s) / 2,
          b.top + (b.height() - _bmp.getHeight() * s) / 2);
      canvas.save();
      canvas.clipRect(b);
      canvas.drawBitmap(_bmp, _matrix, _paint);
      if (_dim.getAlpha() > 0)
        canvas.drawRect(b, _dim);
      canvas.restore();
    }

    @Override public void setAlpha(int a) { _paint.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { _paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}
