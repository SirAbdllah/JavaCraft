#!/usr/bin/env python3
"""Build JavaCraft's 512px tile icon from the official Minecraft grass-block icon."""
import pathlib
import struct
import zlib


ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "artwork" / "minecraft-grass-block-source.png"
OUTPUTS = [ROOT / "artwork" / "icon0.png",
           ROOT / "dist" / "PS5" / "data" / "homebrew" / "PPSA99995" / "sce_sys" / "icon0.png"]
SIZE = 512


def decode_png(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Not a PNG file: {path}")
    offset = 8
    compressed = bytearray()
    while offset < len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + length]
        offset += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", chunk)
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
    if bit_depth != 8 or color_type not in (2, 6) or interlace != 0:
        raise ValueError("The Minecraft source icon must be a non-interlaced RGB/RGBA PNG.")
    bpp = 4 if color_type == 6 else 3
    stride = width * bpp
    raw = zlib.decompress(compressed)
    rows = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        row = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            left = row[i - bpp] if i >= bpp else 0
            up = previous[i]
            upper_left = previous[i - bpp] if i >= bpp else 0
            if filter_type == 1:
                row[i] = (row[i] + left) & 255
            elif filter_type == 2:
                row[i] = (row[i] + up) & 255
            elif filter_type == 3:
                row[i] = (row[i] + ((left + up) >> 1)) & 255
            elif filter_type == 4:
                p = left + up - upper_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upper_left)
                predictor = left if pa <= pb and pa <= pc else up if pb <= pc else upper_left
                row[i] = (row[i] + predictor) & 255
            elif filter_type != 0:
                raise ValueError(f"Unknown PNG filter {filter_type}.")
        rows.append(row)
        previous = row
    rgba = bytearray(width * height * 4)
    for y, row in enumerate(rows):
        for x in range(width):
            src = x * bpp
            dst = (y * width + x) * 4
            rgba[dst:dst + 3] = row[src:src + 3]
            rgba[dst + 3] = row[src + 3] if bpp == 4 else 255
    return width, height, rgba


def save_png(path, width, height, rgba):
    def chunk(kind, payload):
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xffffffff)

    scanlines = bytearray()
    stride = width * 4
    for y in range(height):
        scanlines.append(0)
        scanlines.extend(rgba[y * stride:(y + 1) * stride])
    encoded = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(scanlines, 9))
               + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def main():
    src_w, src_h, source = decode_png(SOURCE)
    icon_size = 432
    margin = (SIZE - icon_size) // 2
    background = bytearray(SIZE * SIZE * 4)
    cube = bytearray(SIZE * SIZE * 4)
    for y in range(SIZE):
        for x in range(SIZE):
            i = (y * SIZE + x) * 4
            shade = 15 + (y * 8 // SIZE)
            background[i:i + 4] = bytes((shade + 5, shade + 2, shade + 17, 255))
    for y in range(icon_size):
        sy = min(src_h - 1, y * src_h // icon_size)
        for x in range(icon_size):
            sx = min(src_w - 1, x * src_w // icon_size)
            si = (sy * src_w + sx) * 4
            di = ((margin + y) * SIZE + margin + x) * 4
            cube[di:di + 4] = source[si:si + 4]

    # Two narrow scanline tears offset the original block texture.
    for y0, y1, shift, tint in ((104, 118, 15, (35, 235, 255)),
                                (256, 270, -13, (255, 42, 190)),
                                (360, 367, 8, (40, 230, 255))):
        for y in range(y0, y1):
            for x in range(SIZE):
                sx = x - shift
                di = (y * SIZE + x) * 4
                if 0 <= sx < SIZE:
                    si = (y * SIZE + sx) * 4
                    if cube[si + 3]:
                        background[di:di + 4] = cube[si:si + 4]
                # A thin colored edge separates the shifted scanline from its original.
                if cube[di + 3]:
                    r, g, b, _ = cube[di:di + 4]
                    background[di:di + 4] = bytes(((r * 2 + tint[0]) // 3,
                                                   (g * 2 + tint[1]) // 3,
                                                   (b * 2 + tint[2]) // 3, 255))

    for y in range(SIZE):
        for x in range(SIZE):
            i = (y * SIZE + x) * 4
            if cube[i + 3] and not (104 <= y < 118 or 256 <= y < 270 or 360 <= y < 367):
                background[i:i + 4] = cube[i:i + 4]
    # Fine RGB glitch fragments at the outer edge of the icon.
    for y, left, right, color in ((112, 54, 96, (36, 225, 255, 255)),
                                   (263, 408, 458, (255, 42, 190, 255)),
                                   (363, 92, 133, (36, 225, 255, 255))):
        for x in range(left, right):
            i = (y * SIZE + x) * 4
            background[i:i + 4] = bytes(color)

    for output in OUTPUTS:
        save_png(output, SIZE, SIZE, background)
        print(f"Wrote {output}")


if __name__ == "__main__":
    main()
