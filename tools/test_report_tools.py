#!/usr/bin/env python3
"""Focused regression tests for the standalone analyzer/report schema.

Run with:

    python3 -m unittest tools/test_report_tools.py

The real 99-container corpus pass remains the integration test.  These small
records cover the dispatch types that the vendor corpus never emits and pin the
byte-level mistakes most likely to regress (generic pointer guessing, unsigned
geometry, and grouped setting/font fields).
"""

from __future__ import annotations

import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from analyze_container import (
    FMT_RGB565,
    IMAGE_TRAILER,
    decode_widget,
    parse_font_binding,
    parse_setting,
    scan_images,
)
from watchface_schema import REFERENCE_WIDGETS, WIDGET_TYPES


FIXED_SIZES = {
    1: 40, 2: 44, 5: 56, 6: 76, 7: 52, 13: 100, 16: 60, 17: 50,
}


def record(type_id: int) -> bytes:
    if type_id == 3:
        size = 40
    elif type_id == 4:
        size = 44
    elif type_id == 9:
        size = 28
    elif type_id in (8, 10, 11, 12, 14, 15):
        size = 16
    else:
        size = FIXED_SIZES[type_id]
    raw = bytearray(size)
    source = 75 if type_id == 9 else 0
    struct.pack_into("<III", raw, 0, type_id, source, 0)
    struct.pack_into("<I", raw, 0x0C, size)

    if size >= 0x20:
        struct.pack_into("<hhhh", raw, 0x18, -3, 4, -5, 6)
    if type_id in (1, 2):
        struct.pack_into("<HH", raw, 0x1C, 0xFFFF, 0)
    if type_id in (5, 13):
        struct.pack_into("<HH", raw, 0x20, 0xFFFF, 0)
    if type_id == 3:
        raw[0x20] = 1
        struct.pack_into("<I", raw, 0x24, 0)
    if type_id == 4:
        struct.pack_into("<I", raw, 0x20, 3)
        struct.pack_into("<BBH", raw, 0x24, 1, 0xFF, 100)
        struct.pack_into("<I", raw, 0x28, 0)
    if type_id == 5:
        struct.pack_into("<H", raw, 0x2C, 0xFFFF)
    if type_id == 6:
        struct.pack_into("<H", raw, 0x3C, 0xFFFF)
        struct.pack_into("<H", raw, 0x40, 6)
    if type_id == 13:
        for part in range(4):
            base = 0x24 + part * 12
            struct.pack_into("<HHHHBBH", raw, base, 0xFFFF, 0xFFFF, 0xFFFF,
                             0xFFFF, 0, 0, 0)
        struct.pack_into("<H", raw, 0x62, 0)
    if type_id == 16:
        struct.pack_into("<H", raw, 0x24, 1)
        struct.pack_into("<I", raw, 0x34, 0)
    if type_id == 17:
        struct.pack_into("<hh", raw, 0x24, 0, 100)
        struct.pack_into("<I", raw, 0x2C, 0)
        raw[0x30:0x32] = bytes((6, 1))
    return bytes(raw)


class WidgetSchemaTest(unittest.TestCase):
    def test_all_17_dispatch_records_account_for_every_byte(self) -> None:
        for type_id in WIDGET_TYPES:
            with self.subTest(type_id=type_id):
                decoded = decode_widget(record(type_id), 0, 0x18)
                self.assertEqual(type_id, decoded["type"])
                self.assertEqual(0, decoded["record_unclassified_bytes"])
                self.assertEqual(0, decoded["record_overlapping_bytes"])

    def test_reference_sample_counts_are_the_4034_vendor_records(self) -> None:
        self.assertEqual(
            REFERENCE_WIDGETS,
            sum(schema["reference_samples"] for schema in WIDGET_TYPES.values()),
        )

    def test_only_authoritative_types_emit_image_edges(self) -> None:
        expected = {1: 1, 2: 1, 3: 1, 4: 1, 16: 1, 17: 1}
        for type_id in WIDGET_TYPES:
            with self.subTest(type_id=type_id):
                decoded = decode_widget(record(type_id), 0, 0)
                self.assertEqual(expected.get(type_id, 0),
                                 len(decoded["image_pointer_values"]))

    def test_pair_zero_words_are_not_false_image_zero_references(self) -> None:
        decoded = decode_widget(record(5), 0, 0)
        self.assertEqual([], decoded["image_pointer_values"])

    def test_animation_repeat_ff_remains_unsigned_255(self) -> None:
        decoded = decode_widget(record(4), 0, 0)
        repeat = next(field for field in decoded["decoded_fields"]
                      if field["name"] == "repeat_count")
        self.assertEqual(255, repeat["value"])

    def test_signed_extents_are_not_reinterpreted_as_u16(self) -> None:
        for type_id in (5, 6, 13, 16, 17):
            with self.subTest(type_id=type_id):
                decoded = decode_widget(record(type_id), 0, 0)
                width = next(field for field in decoded["decoded_fields"]
                             if field["name"] == "width")
                self.assertEqual(-5, width["value"])


class ResourceSchemaTest(unittest.TestCase):
    def test_setting_uses_signed_version_and_four_independent_bytes(self) -> None:
        raw = bytearray(256)
        raw[:5] = b"LQ_WF"
        struct.pack_into("<I", raw, 0x0C, 0x12345678)
        raw[0x10:0x16] = b"90001\0"
        struct.pack_into("<i", raw, 0x30, -123)
        raw[0x34:0x38] = bytes((3, 2, 0xFE, 0xFD))
        parsed = parse_setting(bytes(raw))
        self.assertEqual(-123, parsed["face_version"])
        self.assertEqual((3, 2, 0xFE, 0xFD),
                         (parsed["style_count"], parsed["default_style"],
                          parsed["property_0x36"], parsed["property_0x37"]))

    def test_font_record_is_72_selectors_not_one_plus_opaque(self) -> None:
        raw = bytearray(92)
        raw[1] = 3
        raw[68] = 15
        raw[0x48:0x50] = b"WF_VALUE"
        struct.pack_into("<I", raw, 0x58, 32)
        parsed = parse_font_binding(bytes(raw))
        self.assertEqual(72, len(parsed["family_selectors"]))
        self.assertEqual(3, parsed["byte_1_override"])
        self.assertEqual(15, parsed["family_selectors"][68])
        self.assertEqual(32, parsed["requested_pixel_size"])

    def test_image_requires_exact_four_byte_trailer(self) -> None:
        pixels = bytes((0x00, 0xF8))
        body = pixels + bytes(IMAGE_TRAILER)
        image = struct.pack("<HHHHI", 1, 1, FMT_RGB565, 0, len(body)) + body
        parsed, error = scan_images(image, 0, len(image), "synthetic")
        self.assertIsNone(error)
        self.assertEqual(1, len(parsed))
        self.assertEqual(4, parsed[0]["trailer_size"])
        self.assertTrue(parsed[0]["trailer_zero"])

        malformed = image[:-1]
        _parsed, error = scan_images(malformed, 0, len(malformed), "synthetic")
        self.assertIsNotNone(error)


if __name__ == "__main__":
    unittest.main()
