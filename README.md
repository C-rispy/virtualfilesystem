# Zest Virtual File System (ZVFS) — Binary Filesystem in Python & Java

A compact **virtual filesystem stored in a single binary file** (`.zvfs`), implemented in **Python** and **Java** with a shared on-disk format. ZVFS supports creating a filesystem image, storing and extracting files, logical deletion, listing, printing contents, and defragmentation.

The focus is on **explicit binary layout management**: fixed-size metadata, fixed-size directory entries, **byte offsets**, **little-endian encoding**, and **64-byte alignment**—plus ensuring the Python and Java implementations stay bit-compatible.

> Developed as part of *Software Construction* (University of Zurich).
> This repository is a personal portfolio version.

---

## Overview

A `.zvfs` image contains:

* **Header (64 bytes)**: global filesystem metadata
* **File Entry Table (32 × 64 bytes)**: fixed-capacity directory entries
* **Data Region (variable)**: raw file payloads, **64-byte aligned**

Constraints:

* Max **32 files**
* Max filename length **31 bytes** (UTF-8, null-terminated)
* Max stored file size **4 GB**
* Deletion is **logical** (mark entry deleted); compaction happens during defrag

---

## On-Disk Format

### Header (64 bytes)

Tracks core invariants:

* magic (`ZVFSDSK1`), version
* active file count, deleted count, capacity
* offsets for file table and data region
* `next_free_offset` (append pointer, **must remain aligned**)
* `free_entry_offset` (next usable directory slot, if any)

### File Entry (64 bytes × 32)

Each entry stores:

* name (32 bytes: 31 chars + `\0`)
* start offset (aligned)
* length (bytes)
* type (reserved, set to 0)
* flag (0 = active, 1 = deleted)
* created timestamp (UNIX time)

### Data Region

Raw bytes appended at `next_free_offset`, padded with zeroes up to the next 64-byte boundary.

---

## Commands

Supported by **both implementations**:

```bash
mkfs    # create a new filesystem image
gifs    # print filesystem metadata (counts, free entries, total size)
addfs   # import a host file into the image (append + new directory entry)
getfs   # export a file from the image back to disk
rmfs    # mark a directory entry as deleted (no data movement)
lsfs    # list active entries (name, size, timestamp)
catfs   # print contents of a stored file
dfrgfs  # compact: remove deleted entries + pack data region
```

### Python

```bash
python zvfs.py mkfs filesystem1.zvfs
python zvfs.py addfs filesystem1.zvfs hello.txt
python zvfs.py lsfs filesystem1.zvfs
```

### Java

```bash
javac zvfs.java
java zvfs mkfs filesystem2.zvfs
java zvfs addfs filesystem2.zvfs hello.txt
java zvfs lsfs filesystem2.zvfs
```

---

## Cross-Language Compatibility

Python and Java read/write the *same* bytes on disk:

* **Little-endian** everywhere
* Fixed struct sizes (64-byte header, 64-byte entries)
* Identical alignment rules and null-terminated name encoding

That means you can:

* create an image in Python and manipulate it in Java
* create an image in Java and read/extract files in Python

---

## Defragmentation Model

`rmfs` is intentionally cheap: it only flips a deletion flag and updates header counters.
`dfrgfs` performs the expensive work:

* scans entries, keeps active files
* rewrites the file table densely
* copies file payloads to remove alignment gaps
* updates offsets (`next_free_offset`, `free_entry_offset`) and clears deleted count

This matches the usual “fast delete, occasional compact” approach used in real storage systems (at toy scale).

---

## Implementation Notes

* **Python** uses `struct.pack/unpack` for deterministic binary serialization.
* **Java** uses `FileChannel` + `ByteBuffer` (little-endian) and manual buffer positioning.
* Special care is needed in Java for:

  * buffer cursor state (`position`, `flip`, `wrap/allocate`)
  * encoding names into a fixed 32-byte field
  * avoiding silent offset mistakes when reading/writing with `FileChannel`

---

## Repository Layout

```bash
zvfs.py           # Python implementation (struct-based)
zvfs.java         # Java implementation (NIO FileChannel/ByteBuffer)
zvfs.class        # Compiled Java bytecode
filesystem1.zvfs  # Image created via Python
filesystem2.zvfs  # Image created via Java
README.md         # This file
```

---

## Attribution

This repository contains my personal portfolio version.

Original course collaboration credits:

* Thierry Mathys
* Bleron Neziri
* Cynthia Ka Ong
