package juloo.keyboard2;

/** Text logic of the one-shot keys "delete inside", "delete around",
    "select inside" and "jump forward/backward". Pure functions on strings,
    independent from Android. */
public final class TextTargets
{
  /** How much text around the cursor is looked at. */
  public static final int MAX_CHARS = 5000;

  static final String OPENING = "([{<«「『“‘‹";
  static final String CLOSING = ")]}>»」』”’›";

  public static boolean is_target_action(KeyValue.Editing e)
  {
    switch (e)
    {
      case DELETE_INSIDE:
      case DELETE_AROUND:
      case SELECT_INSIDE:
      case JUMP_FORWARD:
      case JUMP_BACKWARD:
        return true;
      default:
        return false;
    }
  }

  /** Find the pair of delimiters around [sel_start, sel_end[ in [text].
      [target] is either side of a bracket pair or any other character, which
      is then used on both sides (quotes, '*', '|'...). If the cursor isn't
      inside a pair, the next pair after it is used. Returns the indexes of
      the opening and closing delimiters, or [null]. */
  public static int[] find_pair(String text, int sel_start, int sel_end,
      char target)
  {
    char open = target, close = target;
    int i = OPENING.indexOf(target);
    int j = CLOSING.indexOf(target);
    if (i >= 0)
      close = CLOSING.charAt(i);
    else if (j >= 0)
      open = OPENING.charAt(j);
    if (open == close)
      return find_symmetric(text, sel_start, sel_end, open);
    int l = find_open(text, sel_start, open, close);
    int r = (l < 0) ? -1 : find_close(text, sel_end, open, close);
    if (l >= 0 && r >= 0)
      return new int[]{ l, r };
    // Not inside a pair: use the next one.
    l = text.indexOf(open, sel_end);
    if (l < 0)
      return null;
    r = find_close(text, l + 1, open, close);
    return (r < 0) ? null : new int[]{ l, r };
  }

  /** Index of the unmatched [open] before [pos], or -1. */
  static int find_open(String text, int pos, char open, char close)
  {
    int depth = 0;
    for (int i = pos - 1; i >= 0; i--)
    {
      char c = text.charAt(i);
      if (c == close)
        depth++;
      else if (c == open)
      {
        if (depth == 0)
          return i;
        depth--;
      }
    }
    return -1;
  }

  /** Index of the unmatched [close] at or after [pos], or -1. */
  static int find_close(String text, int pos, char open, char close)
  {
    int depth = 0;
    for (int i = pos; i < text.length(); i++)
    {
      char c = text.charAt(i);
      if (c == open)
        depth++;
      else if (c == close)
      {
        if (depth == 0)
          return i;
        depth--;
      }
    }
    return -1;
  }

  static int[] find_symmetric(String text, int sel_start, int sel_end, char d)
  {
    int l = text.lastIndexOf(d, sel_start - 1);
    int r = text.indexOf(d, sel_end);
    if (l >= 0 && r >= 0)
      return new int[]{ l, r };
    // Cursor before the first delimiter: use the next pair.
    if (r >= 0)
    {
      int r2 = text.indexOf(d, r + 1);
      if (r2 >= 0)
        return new int[]{ r, r2 };
    }
    return null;
  }

  /** Distance to move the cursor so that it lands just before the next
      ([forward]) or previous occurrence of [target]. An occurrence right
      after the cursor is skipped when going forward so that repeated jumps
      make progress. Letters are matched ignoring case. Returns [null] if
      there's no occurrence. */
  public static Integer jump(String before, String after, boolean forward,
      char target)
  {
    if (forward)
    {
      for (int i = 1; i < after.length(); i++)
        if (same(after.charAt(i), target))
          return i;
    }
    else
    {
      for (int i = before.length() - 1; i >= 0; i--)
        if (same(before.charAt(i), target))
          return i - before.length();
    }
    return null;
  }

  /** Characters to delete before and after the cursor to remove the word
      it's in (letters, digits, '_' and combining marks such as harakat). If
      the cursor is between spaces, the run of spaces is removed instead.
      Returns [null] if there's nothing to delete. */
  public static int[] word_around(String before, String after)
  {
    boolean word = (before.length() > 0 && is_word(before.charAt(before.length() - 1)))
      || (after.length() > 0 && is_word(after.charAt(0)));
    int l = before.length();
    while (l > 0 && class_matches(before.charAt(l - 1), word))
      l--;
    int r = 0;
    while (r < after.length() && class_matches(after.charAt(r), word))
      r++;
    int nb = before.length() - l;
    if (nb + r == 0)
      return null;
    return new int[]{ nb, r };
  }

  static boolean class_matches(char c, boolean word)
  {
    return word ? is_word(c) : (c == ' ' || c == '\t');
  }

  static boolean is_word(char c)
  {
    if (Character.isLetterOrDigit(c) || c == '_')
      return true;
    int t = Character.getType(c);
    return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
      || t == Character.ENCLOSING_MARK;
  }

  static boolean same(char a, char b)
  {
    return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
  }
}
