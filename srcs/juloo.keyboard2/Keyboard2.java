package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.dict.DictionarySwitcher;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** Preference keys storing the last floating window position. */
  private static final String PREF_FLOATING_X = "floating_x";
  private static final String PREF_FLOATING_Y = "floating_y";
  /** Fraction of the screen height used as the default top offset. */
  private static final float DEFAULT_FLOATING_Y_RATIO = 0.25f;

  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private Suggestions _suggestions;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;

  /** Theme background of [_keyboard_container_view]. */
  private Drawable _default_background = null;

  /** Drag state of the floating keyboard handle. */
  private float _dragStartX;
  private float _dragStartY;
  private int _windowStartX;
  private int _windowStartY;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  /** Lists the enabled layouts for the picker shown when holding space. */
  final Keyboard2View.LayoutPickerCallback _layout_picker =
    new Keyboard2View.LayoutPickerCallback()
    {
      public String[] layout_names()
      {
        if (_currentSpecialLayout != null) // Numpad, emoji...
          return null;
        int n = _config.layouts.size();
        String[] names = new String[n];
        for (int i = 0; i < n; i++)
        {
          KeyboardData l = _config.layouts.get(i);
          if (l == null)
            l = _localeTextLayout;
          names[i] = (l == null || l.name == null) ? "System" : l.name;
        }
        return names;
      }

      public int current_layout()
      {
        int i = _config.get_current_layout();
        return (i < _config.layouts.size()) ? i : 0;
      }

      public void select_layout(int i)
      {
        setTextLayout(i);
      }
    };

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    // The active dictionary depends on the current layout.
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboard_layout_view.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadNumericLayout()
  {
    return loadNumpad(_config.orientation_landscape ?
        R.xml.numeric_landscape : R.xml.numeric);
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    Receiver recvr = this.new Receiver();
    _suggestions = new Suggestions(recvr, _config);
    _keyeventhandler = new KeyEventHandler(_config, recvr, _suggestions);
    KeyValue.Stateful._handler = recvr;
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _default_background = null;
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);
    _keyboard_layout_view.set_layout_picker(_layout_picker);
    View handle = _keyboard_container_view.findViewById(R.id.floating_handle);
    handle.setOnTouchListener(_floatingResizeTouchListener);
    View resize = _keyboard_container_view.findViewById(R.id.floating_resize);
    resize.setOnTouchListener(_floatingResizeTouchListener);
    View dock_resize = _keyboard_container_view.findViewById(R.id.docked_resize);
    dock_resize.setOnTouchListener(_dockedResizeTouchListener);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.device_locales = DeviceLocales.load(this);
    if (_config.device_locales.default_ != null)
    {
      String layout_name = _config.device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _config.device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.should_show_dictionary_switch =
      (_config.device_locales.installed.size() > 0);
    String dict_name = _dictionaries.get_selected(_config);
    if (dict_name == null)
      dict_name = (_config.device_locales.default_ != null) ?
        _config.device_locales.default_.dictionary : null;
    _dictionaries.set_current_dictionary(_config, dict_name);
  }

  /** Remember and apply the dictionary chosen by the user for the current
      context. */
  private void select_dictionary(String dict_name)
  {
    _dictionaries.set_selected(_config, dict_name);
    refresh_current_dictionary();
    refresh_candidates_view();
  }

  private void refresh_candidates_view()
  {
    boolean should_show =
      _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && !_config.split_layout
      // Without a dictionary the strip only shows "Change language": hide
      // it, except in floating mode where it holds the floating toggle.
      && (_config.current_dictionary != null || _config.floating_keyboard);
    if (should_show)
    {
      _candidates_view.refresh_config(_config);
      _keyeventhandler.dictionary_changed();
    }
    _candidates_view.setVisibility(should_show ? View.VISIBLE : View.GONE);
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background: the user's picture if any, otherwise the
    // theme color. Opacity applies to both.
    if (_default_background == null)
      _default_background = _keyboard_container_view.getBackground();
    android.graphics.Bitmap pic = CustomAssets.background(this);
    if (pic == null && "pigeon".equals(_config.theme_name))
      pic = CustomAssets.theme_background(this, R.drawable.pigeon_background);
    Drawable bg = (pic != null)
      ? new CustomAssets.CoverDrawable(pic, _config.background_dim)
      : _default_background.mutate();
    bg.setAlpha(_config.keyboardOpacity);
    _keyboard_container_view.setBackground(bg);
    KeySounds.instance(this).refresh(_config);
    _keyboard_layout_view.reset();
    updateFloatingHandleVisibility();
    refresh_candidates_view();
  }

  /** Show or hide the floating keyboard drag handle. */
  private void updateFloatingHandleVisibility()
  {
    View handle = _keyboard_container_view.findViewById(R.id.floating_handle);
    int vis = _config.floating_keyboard ? View.VISIBLE : View.GONE;
    if (handle != null)
      handle.setVisibility(vis);
    View resize = _keyboard_container_view.findViewById(R.id.floating_resize);
    if (resize != null)
      resize.setVisibility(vis);
    View dock_resize = _keyboard_container_view.findViewById(R.id.docked_resize);
    if (dock_resize != null)
      dock_resize.setVisibility(_config.floating_keyboard ? View.GONE : View.VISIBLE);
  }

  /** Toggle floating keyboard mode on and off from the keyboard. */
  private void toggleFloatingKeyboard()
  {
    Config.globalPrefs().edit()
      .putBoolean("floating_keyboard", !_config.floating_keyboard)
      .apply();
    refresh_config();
    _keyboard_layout_view.setKeyboard(current_layout());
    updateSoftInputWindowLayoutParams();
    updateFloatingHandleVisibility();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN:
          return loadPinentry(_config.orientation_landscape ?
              R.xml.pin_landscape : R.xml.pin);
        case NUMBER:
          return loadNumericLayout();
      }
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    _config.editor_config.refresh(info, getResources());
    refresh_config();
    _currentSpecialLayout = refresh_special_layout();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.started();
    setInputView(_keyboard_container_view);
    updateFloatingHandleVisibility();
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setExtractViewShown(boolean shown){
      super.setExtractViewShown(false);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // The IME window always fills the screen. In floating mode the window
    // never moves: only the keyboard panel inside it is translated, which
    // avoids the flicker caused by repositioning a system window.
    WindowManager.LayoutParams wattrs = window.getAttributes();
    if (wattrs.width != ViewGroup.LayoutParams.MATCH_PARENT
        || wattrs.gravity != Gravity.BOTTOM || wattrs.x != 0 || wattrs.y != 0)
    {
      wattrs.width = ViewGroup.LayoutParams.MATCH_PARENT;
      wattrs.gravity = Gravity.BOTTOM;
      wattrs.x = 0;
      wattrs.y = 0;
      window.setAttributes(wattrs);
    }
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);
    final View panel = (View)inputArea.getParent();
    updateLayoutHeightOf(panel,
            isFullscreenMode()
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    if (_config.floating_keyboard)
    {
      updateLayoutWidthOf(panel, floatingWindowWidth());
      updateLayoutGravityOf(panel, Gravity.TOP | Gravity.START);
      Point pos = getFloatingPosition();
      panel.setTranslationX(pos.x);
      panel.setTranslationY(Math.max(pos.y, floatingMinY()));
    }
    else
    {
      updateLayoutWidthOf(panel, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutGravityOf(panel, Gravity.BOTTOM);
      panel.setTranslationX(0);
      panel.setTranslationY(0);
    }
    panel.requestLayout();
  }

  /** The view that contains the keyboard, moved around in floating mode. */
  private View floatingPanel()
  {
    View inputArea = getWindow().getWindow().findViewById(android.R.id.inputArea);
    return (inputArea == null) ? null : (View)inputArea.getParent();
  }

  /** Width of the floating keyboard, in pixels. */
  private int floatingWindowWidth()
  {
    return _config.floating_width;
  }

  /** Position of the floating keyboard, loaded from the preferences or a
      default position (centered horizontally, ~25% from the top). */
  private Point getFloatingPosition()
  {
    SharedPreferences prefs = Config.globalPrefs();
    if (prefs.contains(PREF_FLOATING_X) && prefs.contains(PREF_FLOATING_Y))
      return new Point(prefs.getInt(PREF_FLOATING_X, 0),
          prefs.getInt(PREF_FLOATING_Y, 0));
    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
    int x = (dm.widthPixels - floatingWindowWidth()) / 2;
    int y = (int)(dm.heightPixels * DEFAULT_FLOATING_Y_RATIO);
    return new Point(x, y);
  }

  @Override
  public void onComputeInsets(Insets outInsets)
  {
    super.onComputeInsets(outInsets);
    if (!_config.floating_keyboard)
      return;
    View panel = floatingPanel();
    View decor = getWindow().getWindow().getDecorView();
    if (panel == null)
      return;
    // The app behind is not resized and only the keyboard itself receives
    // touches; the rest of the full-screen IME window lets them through.
    int h = decor.getHeight();
    outInsets.contentTopInsets = h;
    outInsets.visibleTopInsets = h;
    int[] loc = new int[2];
    panel.getLocationInWindow(loc); // Includes the translation
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(loc[0], loc[1],
        loc[0] + panel.getWidth(), loc[1] + panel.getHeight());
  }

  /** Drag the floating keyboard from its handle. Only the view is moved,
      the window stays in place. */
  private final View.OnTouchListener _floatingHandleTouchListener =
    new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        View panel = floatingPanel();
        if (panel == null)
          return false;
        switch (event.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            _dragStartX = event.getRawX();
            _dragStartY = event.getRawY();
            _windowStartX = (int)panel.getTranslationX();
            _windowStartY = (int)panel.getTranslationY();
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
          case MotionEvent.ACTION_MOVE:
          {
            View decor = getWindow().getWindow().getDecorView();
            int dx = (int)(event.getRawX() - _dragStartX);
            int dy = (int)(event.getRawY() - _dragStartY);
            int maxX = Math.max(0, decor.getWidth() - panel.getWidth());
            int maxY = Math.max(0, decor.getHeight() - panel.getHeight());
            panel.setTranslationX(Math.max(0, Math.min(_windowStartX + dx, maxX)));
            int minY = Math.min(floatingMinY(), maxY);
            panel.setTranslationY(Math.max(minY, Math.min(_windowStartY + dy, maxY)));
            return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            Config.globalPrefs().edit()
              .putInt(PREF_FLOATING_X, (int)panel.getTranslationX())
              .putInt(PREF_FLOATING_Y, (int)panel.getTranslationY())
              .apply();
            // Recompute the touchable region at the new position.
            panel.requestLayout();
            v.getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return false;
      }
    };

  /** State of a resize gesture. */
  private boolean _resizeFromLeft;
  private int _resizeStartWidth;
  private float _resizeStartScale;
  private int _resizeStartKbHeight;
  private float _resizeStartTx;
  /** The bottom bar gesture started in the middle: it moves the keyboard. */
  private boolean _bottomBarMoving;
  /** Resizing from a top corner: the bottom edge stays in place. */
  private boolean _resizeFromTop;
  private float _resizeBottom;
  private final View.OnLayoutChangeListener _keepBottomListener =
    new View.OnLayoutChangeListener()
    {
      public void onLayoutChange(View p, int l, int t, int r, int b,
          int ol, int ot, int or, int ob)
      {
        p.setTranslationY(Math.max(floatingMinY(), _resizeBottom - p.getHeight()));
      }
    };

  /** Width of the resize zone at each end of the bottom bar. */
  private int resizeCornerWidth(View bar)
  {
    float d = getResources().getDisplayMetrics().density;
    return (int)Math.min(bar.getWidth() / 4.f, 72 * d);
  }

  /** Top limit for the floating keyboard, below the status bar so that
      dragging it near the top doesn't pull down the notifications. */
  private int floatingMinY()
  {
    View decor = getWindow().getWindow().getDecorView();
    if (VERSION.SDK_INT >= 23 && decor.getRootWindowInsets() != null)
      return decor.getRootWindowInsets().getStableInsetTop();
    return 0;
  }

  /** Resize the floating keyboard from the grips at its bottom corners.
      Dragging sideways changes the width, dragging up or down the height.
      The size is applied live and saved when the finger is lifted. */
  private final View.OnTouchListener _floatingResizeTouchListener =
    new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        View panel = floatingPanel();
        if (panel == null)
          return false;
        View decor = getWindow().getWindow().getDecorView();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
        {
          int corner = resizeCornerWidth(v);
          _bottomBarMoving =
            event.getX() > corner && event.getX() < v.getWidth() - corner;
        }
        if (_bottomBarMoving)
          return _floatingHandleTouchListener.onTouch(v, event);
        switch (event.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            _dragStartX = event.getRawX();
            _dragStartY = event.getRawY();
            _resizeFromLeft = event.getX() < v.getWidth() / 2.f;
            _resizeStartWidth = panel.getWidth();
            _resizeStartScale = _config.floating_height_scale;
            _resizeStartKbHeight = Math.max(1, _keyboard_layout_view.getHeight());
            _resizeStartTx = panel.getTranslationX();
            _resizeFromTop = (v.getId() == R.id.floating_handle);
            if (_resizeFromTop)
            {
              _resizeBottom = panel.getTranslationY() + panel.getHeight();
              panel.addOnLayoutChangeListener(_keepBottomListener);
            }
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
          case MotionEvent.ACTION_MOVE:
          {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float dx = event.getRawX() - _dragStartX;
            float dy = event.getRawY() - _dragStartY;
            // Width: the opposite edge stays in place.
            int maxW = _resizeFromLeft
              ? (int)(_resizeStartTx + _resizeStartWidth)
              : (int)(decor.getWidth() - _resizeStartTx);
            int w = (int)(_resizeStartWidth + (_resizeFromLeft ? -dx : dx));
            w = Math.min(Config.clamp_floating_width(dm, w), maxW);
            if (_resizeFromTop)
              dy = -dy; // Dragging up makes it taller
            float scale = Config.clamp_floating_height_scale(_resizeStartScale
                * (_resizeStartKbHeight + dy) / _resizeStartKbHeight);
            _config.floating_width = w;
            _config.floating_height_scale = scale;
            _config.keyboard_rows_height_pixels =
              (int)(_config.keyboard_rows_height_base * scale);
            if (_resizeFromLeft)
              panel.setTranslationX(_resizeStartTx + _resizeStartWidth - w);
            updateLayoutWidthOf(panel, w);
            _keyboard_layout_view.requestLayout();
            return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
          {
            panel.removeOnLayoutChangeListener(_keepBottomListener);
            // Keep the keyboard on screen after a resize.
            float maxY = Math.max(0, decor.getHeight() - panel.getHeight());
            if (panel.getTranslationY() > maxY)
              panel.setTranslationY(maxY);
            Config.globalPrefs().edit()
              .putInt("floating_width", _config.floating_width)
              .putFloat("floating_height_scale", _config.floating_height_scale)
              .putInt(PREF_FLOATING_X, (int)panel.getTranslationX())
              .putInt(PREF_FLOATING_Y, (int)panel.getTranslationY())
              .apply();
            v.getParent().requestDisallowInterceptTouchEvent(false);
            return true;
          }
        }
        return false;
      }
    };

  /** Docked mode: drag the grip in the top-left corner up or down to change
      the keyboard height. Saved in the usual "keyboard height" setting. */
  private float _dockStartY;
  private int _dockStartRows;
  private int _dockStartKbHeight;
  private final View.OnTouchListener _dockedResizeTouchListener =
    new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        switch (event.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            if (event.getX() > DockedResizeView.touchWidth(v))
              return false;
            _dockStartY = event.getRawY();
            _dockStartRows = _config.keyboard_rows_height_pixels;
            _dockStartKbHeight = Math.max(1, _keyboard_layout_view.getHeight());
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
          case MotionEvent.ACTION_MOVE:
          {
            float ratio = (_dockStartKbHeight - (event.getRawY() - _dockStartY))
              / _dockStartKbHeight;
            int rows = (int)(_dockStartRows * ratio);
            int base = Math.max(1, _config.keyboard_rows_height_base);
            int percent = Math.round(_config.keyboard_height_percent
                * (float)rows / base);
            if (percent < 10 || percent > _config.keyboard_height_max)
              return true;
            _config.keyboard_rows_height_pixels = rows;
            _keyboard_layout_view.requestLayout();
            return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
          {
            int base = Math.max(1, _config.keyboard_rows_height_base);
            int percent = Math.round(_config.keyboard_height_percent
                * (float)_config.keyboard_rows_height_pixels / base);
            percent = Math.max(10, Math.min(percent, _config.keyboard_height_max));
            Config.globalPrefs().edit()
              .putInt(_config.keyboard_height_key, percent).apply();
            v.getParent().requestDisallowInterceptTouchEvent(false);
            return true;
          }
        }
        return false;
      }
    };

  private static void updateLayoutWidthOf(final View view, final int layoutWidth) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.width != layoutWidth) {
      params.width = layoutWidth;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    // Saving the floating window position must not rebuild the keyboard,
    // otherwise it flickers and jumps after each drag.
    if (PREF_FLOATING_X.equals(_key) || PREF_FLOATING_Y.equals(_key))
      return;
    // The settings run in the same process as the keyboard: rebuilding the
    // hidden keyboard on every change made the settings stutter. Everything
    // is refreshed anyway in [onStartInputView].
    if (!isInputViewShown())
      return;
    refresh_config();
    _keyboard_layout_view.setKeyboard(current_layout());
    updateSoftInputWindowLayoutParams();
    updateFloatingHandleVisibility();
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    // Since Android 16, this method returns [false] for unknown reasons.
    if (super.onEvaluateInputViewShown())
      return true;
    if (getResources().getConfiguration().hardKeyboardHidden
        == Configuration.HARDKEYBOARDHIDDEN_NO
        && _config.physical_keyboard_hide)
    {
      Logs.debug("Physical keyboard is present");
      return false;
    }
    return true;
  }

  public void launch_dictionaries_activity()
  {
    start_activity(DictionariesActivity.class);
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    launch_dictionaries_activity();
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver,
         KeyValue.Stateful.Symbol_provider, DictionarySwitcher.Callback
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboard_layout_view.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumericLayout());
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboard_container_view);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case HIDE_SELF:
          Keyboard2.this.requestHideSelf(0);
          break;

        case CHANGE_DICTIONARY:
          new DictionarySwitcher(Keyboard2.this, _dictionaries, this).choose();
          break;

        case TOGGLE_FLOATING:
          toggleFloatingKeyboard();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_target_pending(KeyValue key)
    {
      _keyboard_layout_view.set_armed_key(key);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
    }

    public String provide_stateful_key_symbol(KeyValue.Stateful q)
    {
      switch (q)
      {
        case Complete_first: return _suggestions.suggestions[0];
        case Complete_second: return _suggestions.suggestions[1];
        case Complete_third: return _suggestions.suggestions[2];
        case Complete_emoji: return _suggestions.emoji_suggestion;
      }
      return "";
    }

    public void on_change_dictionary(String dict_name)
    {
      select_dictionary(dict_name);
    }

    public void launch_dictionaries_activity()
    {
      Keyboard2.this.launch_dictionaries_activity();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
}
