package app.iko.notes;

import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Prefer matching CJK serif/sans families when the requested desktop font is absent. */
final class OfficeFonts {
  static void configure(AssetManager assets, File home) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (InputStream in = assets.open("resource-packs/office-fonts.conf")) {
      NotebookArchive.copy(in, buffer, 64 * 1024);
    }
    Files.write(new File(home, "etc/fonts/fonts.conf").toPath(), configuration(buffer.toByteArray(),home));
  }

  static byte[] configuration(byte[] template, File home) {
    String config =
        new String(template, StandardCharsets.UTF_8)
            .replace(
                "/data/data/org.documentfoundation.libreoffice/fontconfig",
                new File(home, "fontconfig").getPath());
    StringBuilder aliases = new StringBuilder();
    alias(
        aliases,
        new String[] {"宋体", "SimSun", "NSimSun", "新宋体", "仿宋", "FangSong", "楷体", "KaiTi"},
        "Noto Serif CJK SC");
    alias(
        aliases,
        new String[] {"黑体", "SimHei", "微软雅黑", "Microsoft YaHei", "等线", "DengXian"},
        "Noto Sans CJK SC");
    aliases.append(
        "<match target=\"font\"><test name=\"weight\""
            + " compare=\"less_eq\"><const>medium</const></test><test target=\"pattern\""
            + " name=\"weight\" compare=\"more_eq\"><const>bold</const></test><edit"
            + " name=\"embolden\" mode=\"assign\"><bool>true</bool></edit><edit name=\"weight\""
            + " mode=\"assign\"><const>bold</const></edit></match>\n");
    config = config.replace("<fontconfig>", "<fontconfig>\n" + aliases);
    return config.getBytes(StandardCharsets.UTF_8);
  }

  private static void alias(StringBuilder xml, String[] families, String replacement) {
    for (String family : families)
      xml.append("<alias binding=\"strong\"><family>")
          .append(family)
          .append("</family><accept><family>")
          .append(replacement)
          .append("</family></accept></alias>\n");
  }
}
