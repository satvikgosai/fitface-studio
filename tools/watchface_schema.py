#!/usr/bin/env python3
"""Shared, evidence-labelled schema for the Fit3 watch-face record types.

This module is deliberately data-only.  ``analyze_container.py`` uses it to
decode records and ``build_report.py`` uses the same objects to draw the visual
reference.  Keeping the field definitions in one place prevents the report from
silently retaining an older, looser interpretation after the analyzer has
learned more.

``read`` means the watch consumes the bytes when it lays out or updates the
widget.  ``unread`` means a full sweep of the type's layout found no consumer;
it does not promise that no firmware anywhere looks at them, so a writer
still preserves whatever the producer put there.
"""

from __future__ import annotations

from collections import OrderedDict

SCHEMA_VERSION = 3
"""Bumped whenever a field's meaning, width or signedness changes.

1 and 2 were the earlier corpus-only models, which described a record as a fixed
36-byte head followed by anonymous 32-bit words.  3 is the per-type layout below.
"""

REFERENCE_CONTAINER_COUNT = 101
REFERENCE_CONTAINER_BYTES = 228_444_504
REFERENCE_WIDGETS = 4_034
REFERENCE_STYLE_IMAGES = 7_306
REFERENCE_BYTE_ROLES = OrderedDict([
    ("format_control", 174_783),
    ("rendered_semantic", 194_965),
    ("content_payload", 227_830_068),
    ("never_read", 244_688),
])
REFERENCE_ROLE_RANGES = 95_906


def f(offset: int, size: int | str, name: str, encoding: str, role: str,
      access: str, description: str) -> dict:
    return {
        "offset": offset,
        "size": size,
        "name": name,
        "encoding": encoding,
        "role": role,
        "access": access,
        "description": description,
    }


COMMON_FIELDS = [
    f(0x00, 4, "type", "u32", "dispatch", "loader-read",
      "Type id; Exactly 1..17 are accepted."),
    f(0x04, 4, "source_id", "u32", "runtime", "type-dependent",
      "Live-data source owned by firmware. Static reads only the low u16; "
      "Animation and Comp do not consume this common word."),
    f(0x08, 4, "reserved_08", "u32", "unread", "unread",
      "Zero in every catalogue record, and no consumer found."),
    f(0x0C, 2, "record_size", "u16", "structure", "loader-read",
      "Low halfword: how far to the next record."),
    f(0x0E, 2, "global_index", "u16", "identity", "resolver-read",
      "Record-order index that relative alignment refers to; not a stable identity."),
    f(0x10, 4, "reserved_10", "u32", "unread", "type-dependent",
      "Normally zero and unread; type 9 copies only the byte at +0x10."),
    f(0x14, 4, "reserved_14", "u32", "unread", "unread",
      "Zero in every catalogue record, and no consumer found."),
]


WIDGET_TYPES = OrderedDict([
    (1, {
        "name": "Static", "status": "catalogue + analysis",
        "reference_samples": 681, "size": "40 bytes", "minimum": 40,
        "summary": "One image, optionally aligned to another widget; mode 1 adds a fixed click route.",
        "fields": [
            f(0x18, 2, "align_x", "i16", "geometry", "read", "Absolute X or alignment offset X."),
            f(0x1A, 2, "align_y", "i16", "geometry", "read", "Absolute Y or alignment offset Y."),
            f(0x1C, 2, "align_code", "u16", "alignment", "read", "Alignment code; 0xFFFF disables relative alignment."),
            f(0x1E, 2, "align_target", "u16", "reference", "read", "Target global record index when alignment is enabled."),
            f(0x20, 4, "image", "u32", "image-pointer", "read", "Image-section-relative raster record offset."),
            f(0x24, 4, "interaction_mode", "u32", "interaction", "read", "Exactly 1 makes the image tappable, on a route the record cannot choose; 0 is passive."),
        ],
        "notes": ["Only the low u16 at common +0x04 is registered; +0x06/+0x07 are unread."],
    }),
    (2, {
        "name": "Hand", "status": "catalogue + analysis",
        "reference_samples": 469, "size": "44 bytes", "minimum": 44,
        "summary": "Raster hand rotated over a live value range about a signed pivot.",
        "fields": [
            f(0x18, 2, "align_x", "i16", "geometry", "read", "Position/alignment offset X."),
            f(0x1A, 2, "align_y", "i16", "geometry", "read", "Position/alignment offset Y."),
            f(0x1C, 2, "align_code", "u16", "alignment", "read", "Alignment code; 0xFFFF disables relative alignment."),
            f(0x1E, 2, "align_target", "u16", "reference", "read", "Target global record index."),
            f(0x20, 2, "pivot_x", "i16", "geometry", "read", "Image rotation pivot X."),
            f(0x22, 2, "pivot_y", "i16", "geometry", "read", "Image rotation pivot Y."),
            f(0x24, 2, "start_angle", "i16", "value-map", "read", "Beginning of live-value angle range."),
            f(0x26, 2, "end_angle", "i16", "value-map", "read", "End of live-value angle range."),
            f(0x28, 4, "image", "u32", "image-pointer", "read", "Image-section-relative hand raster offset."),
        ],
        "sources": [1, 9, 13, 17, 21, 29, 37, 41, 48, 70, 71],
    }),
    (3, {
        "name": "Sprite", "status": "catalogue + analysis",
        "reference_samples": 1_518, "size": "0x24 + 4 × frame_count", "minimum": 40,
        "summary": "Selects an ordered raster frame from a live value.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Position X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Position Y."),
            f(0x1C, 4, "unread_1c", "bytes", "unread", "unread", "Not read."),
            f(0x20, 1, "frame_count", "u8", "structure", "read", "Must be nonzero; bounds the pointer table."),
            f(0x21, 3, "unread_21", "bytes", "unread", "unread", "Not read."),
            f(0x24, "4 × frame_count", "frames", "u32[]", "image-pointer", "read", "Ordered image-section-relative frame offsets."),
        ],
        "sources": [1, 2, 3, 5, 9, 10, 11, 13, 14, 15, 17, 19, 20, 21,
                    22, 23, 25, 26, 27, 28, 29, 37, 41, 48, 69, 70, 71,
                    106, 107, 109, 110, 115, 117, 118, 119, 125],
        "notes": ["Sources 9 and 13 are explicitly unsupported and only log that they are."],
    }),
    (4, {
        "name": "Animation", "status": "analysis only; no producer sample",
        "reference_samples": 0, "size": "0x28 + 4 × frame_count", "minimum": 44,
        "summary": "Timer-driven ordered image animation with a finite repeat byte.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Position X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Position Y."),
            f(0x1C, 4, "unread_1c", "bytes", "unread", "unread", "Not read."),
            f(0x20, 4, "nominal_mode", "u32", "compat", "dead-overwritten", "Values 2/3 enter setup whose result is immediately overwritten; no lasting effect."),
            f(0x24, 1, "frame_count", "u8", "structure", "read", "Must be nonzero."),
            f(0x25, 1, "repeat_count", "u8", "value-map", "read", "Must be nonzero; 1..255 are finite, including 0xFF."),
            f(0x26, 2, "timer_period", "u16", "timing", "read", "Nonzero timer argument; its time unit is not established."),
            f(0x28, "4 × frame_count", "frames", "u32[]", "image-pointer", "read", "Ordered image-section-relative frame offsets."),
        ],
    }),
    (5, {
        "name": "Pair", "status": "catalogue + analysis",
        "reference_samples": 734, "size": "56 bytes", "minimum": 56,
        "summary": "One live text label: numeric printf or localized dictionary lookup.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Absolute X or alignment offset X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Absolute Y or alignment offset Y."),
            f(0x1C, 2, "width", "i16", "geometry", "read", "Rendered-label width."),
            f(0x1E, 2, "height", "i16", "geometry", "read", "Rendered-label height."),
            f(0x20, 2, "align_code", "u16", "alignment", "read", "Alignment code; 0xFFFF means direct placement."),
            f(0x22, 2, "align_target", "u16", "reference", "read", "Target global record index."),
            f(0x24, 4, "text_colour", "u32", "appearance", "read", "Stored AARRGGBB; RGB used and alpha ignored."),
            f(0x28, 1, "font_binding", "u8", "font-index", "read", "Numbered font_N.bin index; source 116 is an exception and picks its own."),
            f(0x29, 1, "unread_29", "u8", "unread", "unread", "Not read."),
            f(0x2A, 1, "numeric_selector", "u8", "format", "read", "0/1=%d, 2..8=%02d..%08d; >=9 clamps to %d."),
            f(0x2B, 1, "unread_2b", "u8", "unread", "unread", "Not read."),
            f(0x2C, 2, "dictionary_base", "u16", "dictionary-index", "read", "0xFFFF selects numeric mode; otherwise base + live value."),
            f(0x2E, 2, "unread_2e", "u16", "unread", "unread", "Varies across the catalogue, but not read."),
            f(0x30, 8, "unread_30", "bytes", "unread", "unread", "Zero throughout the catalogue, and not read."),
        ],
        "notes": ["Source 116 picks its own font and may add a fixed 256×201 tappable overlay whose destination the record cannot choose."],
    }),
    (6, {
        "name": "VectorArc", "status": "catalogue + analysis",
        "reference_samples": 75, "size": "76 bytes", "minimum": 76,
        "summary": "A progress arc drawn from stored colour, angles and thickness; it names no raster.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Bounding-box X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Bounding-box Y."),
            f(0x1C, 2, "width", "i16", "geometry", "read", "Signed width."),
            f(0x1E, 2, "height", "i16", "geometry", "read", "Signed height."),
            f(0x20, 8, "unread_20", "bytes", "unread", "unread", "Not read."),
            f(0x28, 2, "start_angle", "i16", "value-map", "read", "Signed start angle; negatives normalised by adding 360."),
            f(0x2A, 2, "end_angle", "i16", "value-map", "read", "Signed end angle."),
            f(0x2C, 4, "track_colour_a", "u32", "compat", "unread", "A copy of the track colour; not read."),
            f(0x30, 4, "track_colour_b", "u32", "compat", "unread", "Duplicate track-colour copy; not read."),
            f(0x34, 4, "active_colour", "u32", "appearance", "read", "Stored AARRGGBB; RGB used and alpha ignored."),
            f(0x38, 4, "active_colour_copy", "u32", "compat", "unread", "A copy of the active colour; not read."),
            f(0x3C, 2, "vector_sentinel", "u16", "dispatch", "read", "0xFFFF selects this vector construction path."),
            f(0x3E, 2, "unread_3e", "bytes", "unread", "unread", "Not read; only the low u16 at +0x3C is tested."),
            f(0x40, 2, "thickness", "u16", "appearance", "read", "Nonzero arc thickness."),
            f(0x42, 2, "unread_42", "bytes", "unread", "unread", "Not read."),
            f(0x44, 2, "rounded", "u16", "appearance", "read", "Low halfword boolean rounded-cap option."),
            f(0x46, 2, "thickness_copy", "u16", "compat", "unread", "A copy of the thickness; not read."),
            f(0x48, 4, "unread_48", "bytes", "unread", "unread", "Zero across the catalogue; not read."),
        ],
        "sources": [29, 37, 41, 48, 70, 71, 104, 115],
    }),
    (7, {
        "name": "Badge", "status": "catalogue + analysis",
        "reference_samples": 84, "size": "52 bytes", "minimum": 52,
        "summary": "Vector progress rule interpolated between two signed endpoints.",
        "fields": [
            f(0x18, 2, "x1", "i16", "geometry", "read", "Endpoint 1 X."),
            f(0x1A, 2, "y1", "i16", "geometry", "read", "Endpoint 1 Y."),
            f(0x1C, 2, "x2", "i16", "geometry", "read", "Endpoint 2 X."),
            f(0x1E, 2, "y2", "i16", "geometry", "read", "Endpoint 2 Y."),
            f(0x20, 4, "colour_copy_a", "u32", "compat", "unread", "Not read."),
            f(0x24, 4, "colour_copy_b", "u32", "compat", "unread", "Not read."),
            f(0x28, 4, "active_colour", "u32", "appearance", "read", "Stored AARRGGBB; RGB used and alpha ignored."),
            f(0x2C, 4, "active_colour_copy", "u32", "compat", "unread", "A copy of +0x28; not read."),
            f(0x30, 1, "thickness", "u8", "appearance", "read", "Line thickness."),
            f(0x31, 1, "jump_flag", "u8", "diagnostic", "diagnostic-only", "Nonzero only produces a log line; it has no effect on the face."),
            f(0x32, 2, "rounded", "u16", "appearance", "low-byte-read", "Exactly low byte 1 enables rounded/style option; high byte unread."),
        ],
        "sources": [29, 37, 41, 48, 70, 71, 115],
    }),
    (8, {
        "name": "Reserved8", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "An accepted type id that builds nothing and reads no type-specific byte.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (9, {
        "name": "Source75Group", "status": "analysis only; no producer sample",
        "reference_samples": 0, "size": "at least 28 bytes; producer size unknown", "minimum": 28,
        "summary": "A three-slot complication group driven by source 75; its producer name is absent.",
        "fields": [
            f(0x10, 1, "user_tag", "u8", "compat", "copied-no-consumer", "Copied onto the generated object as a tag; nothing consumes it afterwards."),
            f(0x11, 1, "discarded_11", "u8", "unread", "read-discarded", "Loaded as part of a halfword and then discarded."),
            f(0x12, 6, "unread_12", "bytes", "unread", "unread", "Not read."),
            f(0x18, 2, "x", "i16", "geometry", "read", "Parent position X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Parent position Y."),
            f(0x1C, "to record end", "ignored_tail", "bytes", "unread", "unread", "Ignored."),
        ],
        "sources": [75],
    }),
    (10, {
        "name": "Reserved10", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "Builds nothing and never updates.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (11, {
        "name": "Reserved11", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "Behaves exactly like type 10: it builds nothing.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (12, {
        "name": "Reserved12", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "Behaves exactly like type 10: it builds nothing.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (13, {
        "name": "Comp", "status": "catalogue + analysis",
        "reference_samples": 427, "size": "100 bytes", "minimum": 100,
        "summary": "Four 12-byte localized/numeric mini-programs composed into one text label.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Absolute X or alignment offset X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Absolute Y or alignment offset Y."),
            f(0x1C, 2, "width", "i16", "geometry", "read", "Composite width."),
            f(0x1E, 2, "height", "i16", "geometry", "read", "Composite height."),
            f(0x20, 2, "align_code", "u16", "alignment", "read", "Alignment code; 0xFFFF disables relative alignment."),
            f(0x22, 2, "align_target", "u16", "reference", "read", "Target global record index."),
            f(0x24, 48, "parts", "4 × 12-byte program", "format-program", "read", "Four source/string/numeric mini-programs; expanded below."),
            f(0x54, 4, "unread_54", "bytes", "unread", "unread", "Not read."),
            f(0x58, 4, "text_colour", "u32", "appearance", "read", "Stored AARRGGBB; RGB used and alpha ignored."),
            f(0x5C, 2, "rotation", "u16", "appearance", "read", "0 = an ordinary label; nonzero rotates the text, in tenths of a degree."),
            f(0x5E, 1, "font_binding", "u8", "font-index", "read", "Numbered font_N.bin index."),
            f(0x5F, 1, "unread_5f", "u8", "unread", "unread", "Not read."),
            f(0x60, 1, "letter_spacing", "i8", "appearance", "read", "Signed text spacing value."),
            f(0x61, 1, "unread_61", "u8", "unread", "unread", "Not read."),
            f(0x62, 2, "order_string", "u16", "dictionary-index", "read", "Dictionary item containing 1..4 permutation digits; 0/0xFFFF disables."),
        ],
        "notes": ["The common +0x04 is not consumed; each part owns its own source id."],
    }),
    (14, {
        "name": "Reserved14", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "Behaves exactly like type 10: it builds nothing.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (15, {
        "name": "Reserved15", "status": "inert; no producer sample",
        "reference_samples": 0, "size": "producer size unknown", "minimum": 14,
        "summary": "Behaves exactly like type 10: it builds nothing.",
        "fields": [f(0x10, "to record end", "ignored_tail", "bytes", "unread", "unread", "No type-specific byte is consumed.")],
    }),
    (16, {
        "name": "ImageArc", "status": "catalogue + analysis",
        "reference_samples": 30, "size": "60 bytes", "minimum": 60,
        "summary": "Raster-backed progress arc with signed bounding box and angle range.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Bounding-box X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Bounding-box Y."),
            f(0x1C, 2, "width", "i16", "geometry", "read", "Signed width."),
            f(0x1E, 2, "height", "i16", "geometry", "read", "Signed height."),
            f(0x20, 4, "unread_20", "u32", "unread", "unread", "A compatibility value; not read."),
            f(0x24, 2, "thickness", "u16", "appearance", "read", "Must be nonzero."),
            f(0x26, 2, "orientation", "u16", "appearance", "read", "Passed unchanged to GUI arc setter."),
            f(0x28, 2, "start_angle", "i16", "value-map", "read", "Start angle."),
            f(0x2A, 2, "end_angle", "i16", "value-map", "read", "End angle."),
            f(0x2C, 2, "rounded", "u16", "appearance", "read", "Rounded/style boolean."),
            f(0x2E, 2, "unread_2e", "u16", "unread", "unread", "Zero across the catalogue; not read."),
            f(0x30, 4, "unread_30", "u32", "compat", "unread", "0x0000FFFF across the catalogue; not read."),
            f(0x34, 4, "image", "u32", "image-pointer", "read", "Image-section-relative arc raster offset."),
            f(0x38, 4, "object_style_sentinel", "u32", "compat", "read", "0x0000FFFF enables an additional object-style operation."),
        ],
        "sources": [29, 37, 41, 48, 70, 71, 104, 115],
    }),
    (17, {
        "name": "LineBar", "status": "catalogue + analysis",
        "reference_samples": 16, "size": "50 bytes", "minimum": 50,
        "summary": "Raster-backed linear progress bar with a signed range and rounded radius.",
        "fields": [
            f(0x18, 2, "x", "i16", "geometry", "read", "Bounding-box X."),
            f(0x1A, 2, "y", "i16", "geometry", "read", "Bounding-box Y."),
            f(0x1C, 2, "width", "i16", "geometry", "read", "Signed width."),
            f(0x1E, 2, "height", "i16", "geometry", "read", "Signed height."),
            f(0x20, 4, "unread_20", "u32", "unread", "unread", "Zero across the catalogue; not read."),
            f(0x24, 2, "range_min", "i16", "value-map", "read", "Live-value range minimum."),
            f(0x26, 2, "range_max", "i16", "value-map", "read", "Live-value range maximum."),
            f(0x28, 4, "unread_28", "u32", "unread", "unread", "0x0000FFFF across the catalogue; not read."),
            f(0x2C, 4, "image", "u32", "image-pointer", "read", "Image-relative pointer and object-style sentinel test."),
            f(0x30, 1, "thickness", "u8", "appearance", "read", "Thickness/corner diameter; equals the stored height across the catalogue."),
            f(0x31, 1, "rounded", "u8", "appearance", "read", "Exactly 1 halves thickness to obtain corner radius."),
        ],
        "sources": [29, 37, 41, 48, 70, 71, 115],
    }),
])


SOURCE_LABELS = OrderedDict([
    (0, "constant zero"), (1, "hour"), (2, "hour tens"), (3, "hour units"),
    (5, "AM/PM"), (9, "minute"), (10, "minute tens"), (11, "minute units"),
    (13, "second"), (14, "second tens"), (15, "second units"),
    (17, "weekday"), (18, "day of month"), (19, "day tens"), (20, "day units"),
    (21, "month"), (22, "month tens"), (23, "month units"), (24, "year"),
    (25, "year thousands"), (26, "year hundreds"), (27, "year tens"), (28, "year units"),
    (29, "steps"), (37, "battery percentage"), (41, "heart rate"),
    (48, "calories"), (55, "distance"), (62, "weather temperature"),
    (69, "weather condition/icon"),
    (70, "fourth daily-activity goal metric (vendor label absent)"),
    (71, "active time/minutes"), (72, "floors"), (75, "three-slot group"),
    (102, "blood oxygen / SpO2"), (104, "sleep duration"), (115, "water intake"),
    (116, "secondary-city/time-zone name"),
    (117, "local hour +2"), (118, "local hour +4"), (119, "local hour +6"),
    (120, "weather-description string"), (122, "secondary-zone month"),
    (123, "secondary-zone weekday"), (124, "secondary-zone day"),
    (125, "secondary-zone AM/PM"),
    (106, "secondary-clock hour tens"), (107, "secondary-clock hour units"),
    (109, "secondary-clock minute tens"), (110, "secondary-clock minute units"),
])


FONT_FAMILIES = OrderedDict([
    (0, ("MiSans", "10, 12, 16, 18, 20, 22, 24, 26, 28, 32, 34, 36, 40, 44, 48", "MiSans-30/common fallback")),
    (1, ("Roboto Regular-linked", "12, 14, 16, 20, 22, 24, 26, 28, 30, 32, 34, 36, 40, 48, 50; runtime 86", "family-1 fallback")),
    (2, ("Roboto Bold-linked", "20, 22, 24, 26, 28, 30, 32, 34, 36, 40, 42, 44, 48, 60", "family-2 fallback")),
    (3, ("Roboto Medium-linked", "16, 20, 23, 24, 28, 30, 32, 36, 40, 44, 48; runtime 86", "family-3 fallback")),
    (4, ("compiled family; name absent", "40", "family-4 fallback")),
    (5, ("compiled family; name absent", "24, 38", "family-5 fallback")),
    (7, ("compiled family; name absent", "40", "family-7 fallback")),
    (8, ("compiled family; name absent", "44", "common fallback")),
    (9, ("compiled family; name absent", "20, 24", "family-9 fallback")),
    (10, ("compiled family; name absent", "24", "family-10 fallback")),
    (11, ("compiled family; name absent", "30", "common fallback")),
    (12, ("compiled family; name absent", "34", "family-12 fallback")),
    (15, ("LED Digital", "runtime 32", "family-12 fallback")),
])


OPEN_GAPS = [
    ("Firmware revisions",
     "The layouts here are established against the catalogue and one firmware build; a "
     "different firmware build could read a field this schema calls unread."),
    ("Missing producer samples",
     "Types 4 and 9 have known layouts but no catalogue record, so their producer's "
     "canonical padding, defaults and authoring names are unknown; 8, 10-12 and 14-15 "
     "build nothing at all."),
    ("Names that do not exist in the evidence",
     "Source 70, the hour-offset sources 117-119, font selectors 4/5/7-12 and the two "
     "fixed tap destinations have no name anywhere in the available material."),
    ("Device acceptance",
     "A container can be byte-perfect and still be refused: only an SM-R390 install "
     "proves acceptance, and the phone emulator cannot stand in for the watch."),
    ("Image-record count policy",
     "A container whose image-record count changed can transfer, install and leave the "
     "old face on the watch. Where that is decided is not part of this grammar."),
    ("Transfer edge cases",
     "A payload that is an exact multiple of the 39,600-byte transfer window has never "
     "been watched end to end."),
]


EVIDENCE_LEVELS = OrderedDict([
    ("analysis", "The watch's own handling of the field is established, field by field."),
    ("catalogue", "An invariant or a join reproduced across the vendor containers, their "
                  "rendered previews and their string tables."),
    ("device", "Observed on an SM-R390; the 4 MiB container limit and the "
               "unchanged-image-count rule are both this."),
    ("unread", "A full sweep of the layout found no consumer. Preserve the bytes anyway."),
    ("open", "The evidence is absent and needs another sample or a device run."),
])
