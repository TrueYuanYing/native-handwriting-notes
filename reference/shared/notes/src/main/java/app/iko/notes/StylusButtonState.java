package app.iko.notes;

/** Edge detection shared by hover and contact events: a held button fires only once. */
final class StylusButtonState {
  private final java.util.HashMap<Integer, Integer> motion = new java.util.HashMap<>();
  private final java.util.HashMap<Integer, Integer> keys = new java.util.HashMap<>();

  int update(int deviceId, int pressed) {
    int previous = motion.getOrDefault(deviceId, 0) | keys.getOrDefault(deviceId, 0);
    motion.put(deviceId, pressed);
    return pressed & ~previous;
  }

  boolean key(int deviceId, int bit, boolean down) {
    int before = motion.getOrDefault(deviceId, 0) | keys.getOrDefault(deviceId, 0);
    int held = keys.getOrDefault(deviceId, 0);
    keys.put(deviceId, down ? held | bit : held & ~bit);
    return down && (before & bit) == 0;
  }

  void reset() {
    motion.clear();
    keys.clear();
  }
}
