package juloo.keyboard2;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;
import android.preference.PreferenceManager;

public class SettingsActivity extends PreferenceActivity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
    setup_pick("background_pick", "image/*", REQ_BACKGROUND);
    setup_pick("key_sound_pick", "audio/*", REQ_SOUND_KEY);
    setup_pick("key_sound_special_pick", "audio/*", REQ_SOUND_SPECIAL);
    findPreference("background_remove").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            CustomAssets.file(SettingsActivity.this, CustomAssets.BACKGROUND).delete();
            assets_changed(R.string.pref_background_removed);
            return true;
          }
        });
    findPreference("key_sound_remove").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            CustomAssets.file(SettingsActivity.this, CustomAssets.SOUND_KEY).delete();
            CustomAssets.file(SettingsActivity.this, CustomAssets.SOUND_SPECIAL).delete();
            assets_changed(R.string.pref_key_sound_removed);
            return true;
          }
        });
  }

  static final int REQ_BACKGROUND = 1;
  static final int REQ_SOUND_KEY = 2;
  static final int REQ_SOUND_SPECIAL = 3;

  void setup_pick(String key, final String mime, final int req)
  {
    findPreference(key).setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            try { startActivityForResult(i, req); }
            catch (Exception e) { toast(R.string.pref_custom_asset_failed); }
            return true;
          }
        });
  }

  @Override
  protected void onActivityResult(final int req, int result, final Intent data)
  {
    super.onActivityResult(req, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null)
      return;
    final Uri uri = data.getData();
    new Thread() {
      public void run()
      {
        boolean ok;
        if (req == REQ_BACKGROUND)
          ok = CustomAssets.import_image(SettingsActivity.this, uri);
        else
          ok = CustomAssets.import_sound(SettingsActivity.this, uri,
              req == REQ_SOUND_KEY ? CustomAssets.SOUND_KEY : CustomAssets.SOUND_SPECIAL);
        final boolean ok_ = ok;
        runOnUiThread(new Runnable() {
          public void run()
          {
            assets_changed(ok_ ? R.string.pref_custom_asset_saved
                : R.string.pref_custom_asset_failed);
          }
        });
      }
    }.start();
  }

  /** Make the keyboard reload the picture and sounds. */
  void assets_changed(int msg)
  {
    getPreferenceManager().getSharedPreferences().edit()
      .putLong("custom_assets_stamp", System.currentTimeMillis()).apply();
    toast(msg);
  }

  void toast(int msg)
  {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }
}
