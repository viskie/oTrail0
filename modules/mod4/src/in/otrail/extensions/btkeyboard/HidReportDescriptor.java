package in.otrail.extensions.btkeyboard;

/** HID report descriptor shared by the keyboard, mouse and consumer-control reports. */
final class HidReportDescriptor {
  static final int REPORT_KEYBOARD = 1;
  static final int REPORT_MOUSE = 2;
  static final int REPORT_CONSUMER = 3;

  static final int MOD_CTRL = 1;
  static final int MOD_SHIFT = 2;
  static final int MOD_ALT = 4;
  static final int MOD_GUI = 8;

  static final int MOUSE_LEFT = 1;
  static final int MOUSE_RIGHT = 2;

  static final int MEDIA_PLAY_PAUSE = 205;
  static final int MEDIA_NEXT = 181;
  static final int MEDIA_PREVIOUS = 182;
  static final int MEDIA_VOLUME_UP = 233;
  static final int MEDIA_VOLUME_DOWN = 234;
  static final int MEDIA_MUTE = 226;

  private static final String DESCRIPTOR =
      "05010906A1018501050719E029E715002501750195088102"
          + "95017508810395067508150025650507190029658100C0"
          + "05010902A10185020901A1000509190129031500250175"
          + "019503810275059501810305010930093109381581257F"
          + "750895038106C0C0050C0901A1018503050C1500269C02"
          + "19002A9C02751095018100C0";

  private HidReportDescriptor() {}

  static byte[] combo() {
    byte[] data = new byte[DESCRIPTOR.length() / 2];
    for (int index = 0; index < data.length; index++) {
      int offset = index * 2;
      data[index] = (byte) Integer.parseInt(DESCRIPTOR.substring(offset, offset + 2), 16);
    }
    return data;
  }
}
