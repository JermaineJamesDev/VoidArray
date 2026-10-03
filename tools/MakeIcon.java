import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import javax.imageio.ImageIO;

/**
 * Renders the VoidArray formation mark (keep in sync with AppIcons.Logo) into the Windows icon and README logo.
 * Run from the repository root with JDK 11+:
 *   java tools/MakeIcon.java desktopApp/icons/voidarray.ico docs/logo.png
 */
public class MakeIcon {
    static byte[] render(int size) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = size / 24.0;
        Color gold = new Color(0xC9, 0xA4, 0x5C);
        Color jade = new Color(0x5F, 0xC9, 0xA3);
        g.setColor(new Color(0x0B, 0x0D, 0x14));
        g.fill(new Ellipse2D.Double(0, 0, size, size));
        // Small sizes get relatively thicker lines so the rings stay visible.
        float line = (float) (Math.max(0.8, 18.0 / size) * s);
        g.setStroke(new BasicStroke(line, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.setColor(gold);
        g.draw(new Ellipse2D.Double(1.4 * s, 1.4 * s, 21.2 * s, 21.2 * s));
        Path2D diamond = new Path2D.Double();
        diamond.moveTo(20.2 * s, 12 * s); diamond.lineTo(12 * s, 20.2 * s); diamond.lineTo(3.8 * s, 12 * s); diamond.lineTo(12 * s, 3.8 * s); diamond.closePath();
        g.draw(diamond);
        g.draw(new Rectangle2D.Double(6.2 * s, 6.2 * s, 11.6 * s, 11.6 * s));
        g.setColor(jade);
        g.setStroke(new BasicStroke(line * 1.25f));
        g.draw(new Ellipse2D.Double(8 * s, 8 * s, 8 * s, 8 * s));
        g.fill(new Ellipse2D.Double(10.5 * s, 10.5 * s, 3 * s, 3 * s));
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] images = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) images[i] = render(sizes[i]);

        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            header.put((byte) (sizes[i] == 256 ? 0 : sizes[i])).put((byte) (sizes[i] == 256 ? 0 : sizes[i]));
            header.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            header.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        try (FileOutputStream file = new FileOutputStream(args[0])) {
            file.write(header.array());
            for (byte[] image : images) file.write(image);
        }
        try (FileOutputStream png = new FileOutputStream(args[1])) {
            png.write(render(512));
        }
    }
}
