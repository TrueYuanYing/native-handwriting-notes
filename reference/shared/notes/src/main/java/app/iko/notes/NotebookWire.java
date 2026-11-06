package app.iko.notes;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Typed protobuf/TPL and Apple-framed LZ4. See assets/licenses/inkterop-MIT.txt. */
final class NotebookWire {
  static final class Cursor {
    final ByteBuffer b;

    Cursor(byte[] bytes) {
      b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    int u8() {
      return b.get() & 255;
    }

    int u16() {
      return b.getShort() & 65535;
    }

    int count(int max) throws IOException {
      int n = b.getInt();
      if (n < 0 || n > max) throw new IOException("二进制计数超过限制");
      return n;
    }

    byte[] bytes(int n) throws IOException {
      if (n < 0 || n > b.remaining()) throw new IOException("二进制数据截断");
      byte[] out = new byte[n];
      b.get(out);
      return out;
    }

    String string(int max) throws IOException {
      return new String(bytes(count(max)), StandardCharsets.UTF_8);
    }

    long varint() throws IOException {
      long v = 0;
      for (int shift = 0; shift < 64; shift += 7) {
        int c = u8();
        v |= (long) (c & 127) << shift;
        if ((c & 128) == 0) return v;
      }
      throw new IOException("非法 protobuf varint");
    }
  }

  static List<byte[]> records(byte[] data) throws IOException {
    Cursor c = new Cursor(data);
    List<byte[]> result = new ArrayList<>();
    while (c.b.hasRemaining()) {
      if (result.size() >= 100000) throw new IOException("记录数量超过限制");
      result.add(c.bytes((int) c.varint()));
    }
    return result;
  }

  static Map<Integer, Object> fields(byte[] data) throws IOException {
    Cursor c = new Cursor(data);
    Map<Integer, Object> result = new LinkedHashMap<>();
    while (c.b.hasRemaining()) {
      long tag = c.varint();
      int field = (int) (tag >>> 3);
      if (field == 0) throw new IOException("非法 protobuf 字段");
      Object value;
      switch ((int) (tag & 7)) {
        case 0:
          value = c.varint();
          break;
        case 1:
          value = c.b.getDouble();
          break;
        case 2:
          value = c.bytes((int) c.varint());
          break;
        case 5:
          value = c.b.getFloat();
          break;
        default:
          throw new IOException("未知 protobuf wire type");
      }
      result.put(field, value);
    }
    return result;
  }

  static byte[] data(Map<Integer, Object> f, int n) {
    return f.get(n) instanceof byte[] ? (byte[]) f.get(n) : new byte[0];
  }

  static String text(Map<Integer, Object> f, int n) {
    return new String(data(f, n), StandardCharsets.UTF_8);
  }

  static float number(Map<Integer, Object> f, int n, float fallback) {
    return f.get(n) instanceof Number ? ((Number) f.get(n)).floatValue() : fallback;
  }

  static byte[] appleLz4(byte[] data) throws IOException {
    Cursor c = new Cursor(data);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (c.b.hasRemaining()) {
      String magic = new String(c.bytes(4), StandardCharsets.US_ASCII);
      if (magic.equals("bv4$")) return out.toByteArray();
      if (magic.equals("bv4-")) out.write(c.bytes(c.count(32 * 1024 * 1024)));
      else if (magic.equals("bv41")) {
        int expected = c.count(32 * 1024 * 1024), length = c.count(32 * 1024 * 1024);
        out.write(lz4(c.bytes(length), expected));
      } else throw new IOException("未知 Goodnotes 几何压缩版本");
      if (out.size() > 32 * 1024 * 1024) throw new IOException("几何数据超过限制");
    }
    throw new IOException("Goodnotes 几何缺少终止标记");
  }

  private static byte[] lz4(byte[] data, int expected) throws IOException {
    Cursor c = new Cursor(data);
    byte[] out = new byte[expected];
    int pos = 0;
    while (c.b.hasRemaining()) {
      int token = c.u8(), literals = token >>> 4;
      if (literals == 15) {
        int n;
        do {
          n = c.u8();
          literals += n;
        } while (n == 255);
      }
      if (literals > out.length - pos) throw new IOException("LZ4 literal 越界");
      c.b.get(out, pos, literals);
      pos += literals;
      if (!c.b.hasRemaining()) break;
      int offset = c.u16(), length = (token & 15) + 4;
      if ((token & 15) == 15) {
        int n;
        do {
          n = c.u8();
          length += n;
        } while (n == 255);
      }
      if (offset == 0 || offset > pos || length > out.length - pos)
        throw new IOException("LZ4 match 越界");
      for (int i = 0; i < length; i++) {
        out[pos] = out[pos - offset];
        pos++;
      }
    }
    if (pos != expected) throw new IOException("LZ4 解压长度不符");
    return out;
  }

  static final class Section {
    String type;
    boolean array, structs;
    int width;
    float[] values;
  }

  static List<Section> tpl(byte[] data) throws IOException {
    Cursor c = new Cursor(data);
    if (!Arrays.equals(c.bytes(4), new byte[] {116, 112, 108, 0}) || c.b.getInt() != data.length)
      throw new IOException("无效 TPL header");
    StringBuilder signature = new StringBuilder();
    int ch;
    while ((ch = c.u8()) != 0) {
      if (signature.length() >= 512) throw new IOException("TPL 签名过长");
      signature.append((char) ch);
    }
    String sig = signature.toString();
    List<Section> sections = new ArrayList<>();
    for (int i = 0; i < sig.length(); ) {
      Section s = new Section();
      int count = 1;
      if (sig.startsWith("A(S(", i)) {
        int end = sig.indexOf("))", i + 4);
        if (end < 0) throw new IOException("无效 TPL struct");
        s.type = sig.substring(i + 4, end);
        s.array = s.structs = true;
        i = end + 2;
      } else if (sig.startsWith("A(", i)) {
        if (i + 3 >= sig.length() || sig.charAt(i + 3) != ')')
          throw new IOException("无效 TPL array");
        s.type = sig.substring(i + 2, i + 3);
        s.array = true;
        i += 4;
      } else {
        s.type = sig.substring(i, i + 1);
        i++;
      }
      s.width = s.type.length();
      if (s.width == 0 || !s.type.matches("[vuf]+")) throw new IOException("未知 TPL 类型");
      if (s.array) count = c.count(1000000);
      if ((long) count * s.width > 4000000) throw new IOException("TPL 数组过大");
      s.values = new float[count * s.width];
      for (int j = 0; j < s.values.length; j++) {
        float value = s.type.charAt(j % s.width) == 'v' ? c.u16() : c.b.getFloat();
        if (!Float.isFinite(value)) throw new IOException("TPL 包含非有限数值");
        s.values[j] = value;
      }
      sections.add(s);
    }
    if (c.b.hasRemaining()) throw new IOException("TPL 签名与数据长度不符");
    return sections;
  }

  private NotebookWire() {}
}
