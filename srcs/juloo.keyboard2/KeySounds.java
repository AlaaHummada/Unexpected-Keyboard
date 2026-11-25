package juloo.keyboard2;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import java.io.File;

/** Plays a sound when a key is pressed: the system click sounds or sounds
    chosen by the user (one for normal keys, one for space, enter and
    backspace). */
public final class KeySounds
{
  static KeySounds _instance = null;

  public static KeySounds instance(Context ctx)
  {
    if (_instance == null)
      _instance = new KeySounds(ctx.getApplicationContext());
    return _instance;
  }

  final Context _ctx;
  final AudioManager _audio;
  SoundPool _pool = null;
  int _key_id = 0;
  int _special_id = 0;
  long _loaded_stamp = -1;
  /** Which set of sounds is loaded in [_pool]: "custom" or "pigeon". */
  String _loaded_mode = null;

  KeySounds(Context ctx)
  {
    _ctx = ctx;
    _audio = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
  }

  static long stamp(File f)
  {
    return f.exists() ? (f.lastModified() ^ f.length()) : 0;
  }

  /** Load the custom sounds if they changed. */
  public void refresh(Config config)
  {
    String mode = config.key_sound_mode;
    if (!"custom".equals(mode) && !"pigeon".equals(mode))
      return;
    File k = CustomAssets.file(_ctx, CustomAssets.SOUND_KEY);
    File s = CustomAssets.file(_ctx, CustomAssets.SOUND_SPECIAL);
    long st = stamp(k) * 31 + stamp(s);
    if (_pool != null && mode.equals(_loaded_mode)
        && ("pigeon".equals(mode) || st == _loaded_stamp))
      return;
    if (_pool != null)
      _pool.release();
    _pool = new SoundPool.Builder().setMaxStreams(4)
      .setAudioAttributes(new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build())
      .build();
    if ("pigeon".equals(mode))
    {
      // Built-in sounds, see res/raw/SOUNDS_CREDITS.txt
      _key_id = _pool.load(_ctx, R.raw.pigeon_key, 1);
      _special_id = _pool.load(_ctx, R.raw.pigeon_special, 1);
    }
    else
    {
      _key_id = k.exists() ? _pool.load(k.getPath(), 1) : 0;
      _special_id = s.exists() ? _pool.load(s.getPath(), 1) : 0;
    }
    _loaded_stamp = st;
    _loaded_mode = mode;
  }

  static final KeyValue ENTER = KeyValue.getKeyByName("enter");

  /** A key was pressed. */
  public void play(Config config, KeyValue k)
  {
    if (k == null || "off".equals(config.key_sound_mode))
      return;
    float vol = config.key_sound_volume / 100.f;
    int kind = 0; // 0: normal, 1: space, 2: delete, 3: enter
    if (k.getKind() == KeyValue.Kind.Editing)
    {
      if (k.getEditing() == KeyValue.Editing.SPACE_BAR) kind = 1;
      else if (k.getEditing() == KeyValue.Editing.BACKSPACE) kind = 2;
    }
    else if (k.equals(ENTER))
      kind = 3;
    if ("system".equals(config.key_sound_mode))
    {
      int fx = AudioManager.FX_KEYPRESS_STANDARD;
      if (kind == 1) fx = AudioManager.FX_KEYPRESS_SPACEBAR;
      else if (kind == 2) fx = AudioManager.FX_KEYPRESS_DELETE;
      else if (kind == 3) fx = AudioManager.FX_KEYPRESS_RETURN;
      _audio.playSoundEffect(fx, vol);
      return;
    }
    refresh(config);
    if (_pool == null)
      return;
    int id = (kind != 0 && _special_id != 0) ? _special_id : _key_id;
    if (id != 0)
      _pool.play(id, vol, vol, 1, 0, 1.f);
  }
}
