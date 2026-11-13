"""
twlog.py - TeleWarden-compatible encrypted logger and group copier.

This module combines the useful behavior of mlog.py and gcopy.py:

* .twlog on/off             Enable or disable automatic private-media logging.
* .twsetchat <chat_id>      Set the forum group used by automatic logging.
* .twcopy <src> <dst>       Copy a complete chat into a destination forum group.
* .twcopy <src> <topic> <dst>
                            Copy one forum topic into a destination group.
* .twstop                   Request cancellation of a running copy.
* .twnewkey                 Generate a TeleWarden key and send it to Saved Messages.
* .twsetkey <base64-key>    Set an existing 32-byte base64 key, then delete command.
* .twstatus                 Show configuration and job status.

Encryption policy:

* Ordinary text messages are copied with Message.copy() and remain plaintext.
* Forum/topic names remain plaintext so Telegram topics remain navigable.
* Every media payload is downloaded, encrypted, and uploaded as an opaque document.
* The original filename, caption, MIME type, media type, sender and source IDs are
  encrypted into the caption marker. The marker itself is only a protocol identifier.

The media envelope intentionally matches TeleWarden's StreamCrypto format:

    MAGIC(4) VERSION(1) SALT(8)
    repeated: LENGTH(4, high bit means final) + AES-GCM ciphertext

Each chunk is 1 MiB. AES-GCM uses a deterministic per-chunk nonce derived from the
random stream salt and chunk index, and authenticates the chunk index/final flag.

Python dependency:

    pip install cryptography

The TeleWarden Android side must use the same 32-byte content key and implement the
TDE1 envelope plus the TW1 encrypted metadata-caption format defined below.
"""

from __future__ import annotations

import asyncio
import base64
import json
import mimetypes
import os
import secrets
import shutil
import struct
import tempfile
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError as exc:  # pragma: no cover - handled when the module is loaded
    raise RuntimeError(
        "twlog.py requires the 'cryptography' package. Install it with: pip install cryptography"
    ) from exc

from pyrogram import Client, enums, filters
from pyrogram.errors import FloodWait
from pyrogram.types import Message
from pyrogram.raw import functions

from utils.db import db
from utils.misc import modules_help, prefix
from utils.scripts import format_exc


# -----------------------------------------------------------------------------
# Configuration and persistent state
# -----------------------------------------------------------------------------

DB_NAMESPACE = "custom.twlog"
TEMP_DIR = "twlog_temp"
SEND_DELAY = 1.2
EDIT_INTERVAL = 4.0
FILE_SEM = 8
CHUNK_SIZE = 1024 * 1024

MAGIC = b"TDE1"
VERSION = 1
FINAL_FLAG = 1 << 31
NONCE_LENGTH = 12
SALT_LENGTH = 8
TAG_LENGTH = 16

_running = False
_cancel = False
_flood_until = 0.0
_last_edit = 0.0

# One automatic logging task per source user, as in mlog.py.
_media_cache: dict[int, list[Message]] = defaultdict(list)
_media_tasks: dict[int, asyncio.Task] = {}


def _db_get(key: str, default: Any = None) -> Any:
    return db.get(DB_NAMESPACE, key, default)


def _db_set(key: str, value: Any) -> None:
    db.set(DB_NAMESPACE, key, value)


def _logging_enabled() -> bool:
    return bool(_db_get("status", False))


def _destination_chat() -> int | None:
    value = _db_get("chat")
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _load_key() -> bytes | None:
    value = _db_get("key")
    if not value:
        return None
    try:
        key = base64.urlsafe_b64decode(str(value) + "=" * (-len(str(value)) % 4))
    except Exception:
        return None
    return key if len(key) == 32 else None


def _save_key(key: bytes) -> None:
    if len(key) != 32:
        raise ValueError("TeleWarden keys must be exactly 32 bytes")
    _db_set("key", base64.urlsafe_b64encode(key).decode("ascii").rstrip("="))


def _key_text(key: bytes) -> str:
    return base64.urlsafe_b64encode(key).decode("ascii").rstrip("=")


def _norm_id(value: str | int) -> int:
    number = int(value)
    text = str(number)
    if number > 0 and len(text) >= 13 and text.startswith("100"):
        return -number
    return number


def _safe_name(value: Any) -> str:
    text = str(value or "unnamed")
    for char in '\\/:*?"<>|':
        text = text.replace(char, "_")
    return text.strip() or "unnamed"


def _ts() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")


def _is_service(message: Message) -> bool:
    return bool(
        message.new_chat_members
        or message.left_chat_member
        or message.new_chat_title
        or message.new_chat_photo
        or message.pinned_message
        or message.migrate_to_chat_id
        or message.migrate_from_chat_id
        or getattr(message, "service", None)
    )


def _plain_text(message: Message) -> str | None:
    if _is_service(message) or not message.text:
        return None
    date = message.date.strftime("%Y-%m-%d %H:%M:%S") if message.date else "?"
    sender = (
        message.from_user.full_name if message.from_user else None
    ) or (message.sender_chat.title if message.sender_chat else None) or "Unknown"
    return f"[{date}] {sender}: {message.text}"


# -----------------------------------------------------------------------------
# Flood-wait and status helpers
# -----------------------------------------------------------------------------


def _now() -> float:
    return asyncio.get_running_loop().time()


async def _gate() -> None:
    while _flood_until > _now():
        await asyncio.sleep(min(_flood_until - _now(), 5))


def _note_flood(seconds: int) -> None:
    global _flood_until
    _flood_until = max(_flood_until, _now() + int(seconds) + 1)


async def _progress(status: Message, text: str, force: bool = False) -> None:
    global _last_edit
    if not force and _now() - _last_edit < EDIT_INTERVAL:
        return
    _last_edit = _now()
    try:
        await status.edit(text)
    except FloodWait as error:
        _note_flood(error.value)
    except Exception:
        pass


async def _upload_progress(current: int, total: int, status: Message, label: str, started: float) -> None:
    elapsed = max(_now() - started, 0.001)
    speed = current / elapsed / (1024 * 1024)
    percent = current / total * 100 if total else 0
    filled = min(10, int(percent // 10))
    bar = "█" * filled + "░" * (10 - filled)
    remaining = ""
    if speed and total:
        seconds = int((total - current) / (speed * 1024 * 1024))
        remaining = f" | ETA {seconds // 60}m {seconds % 60}s" if seconds >= 60 else f" | ETA {seconds}s"
    await _progress(
        status,
        f"📤 <b>{label}</b>\n{bar} <b>{percent:.0f}%</b>\n"
        f"{current / 1048576:.1f} / {total / 1048576:.1f} MB | {speed:.1f} MB/s{remaining}",
        force=current >= total,
    )


async def _boost(client: Client) -> None:
    try:
        client.get_file_semaphore = asyncio.Semaphore(FILE_SEM)
        client.save_file_semaphore = asyncio.Semaphore(FILE_SEM)
    except Exception:
        pass


async def _warm_peer(client: Client, chat_id: int) -> None:
    try:
        await client.get_chat(chat_id)
        return
    except Exception:
        pass
    try:
        async for _ in client.get_dialogs():
            pass
    except Exception:
        pass


# -----------------------------------------------------------------------------
# TeleWarden encryption
# -----------------------------------------------------------------------------


def _nonce(salt: bytes, index: int) -> bytes:
    return salt + struct.pack(">I", index)


def _aad(index: int, is_final: bool) -> bytes:
    return struct.pack(">IIB", VERSION, index, 1 if is_final else 0)


def _seal(key: bytes, plaintext: bytes, nonce: bytes, aad: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(aad)
    return encryptor.update(plaintext) + encryptor.finalize() + encryptor.tag


def _encrypt_stream(input_path: str, output_path: str, key: bytes) -> None:
    """Encrypt a file using the same chunked TDE1 layout used by TeleWarden."""
    salt = secrets.token_bytes(SALT_LENGTH)
    with open(input_path, "rb") as source, open(output_path, "wb") as target:
        target.write(MAGIC + bytes([VERSION]) + salt)
        current = source.read(CHUNK_SIZE)
        index = 0
        while True:
            following = source.read(CHUNK_SIZE) if len(current) == CHUNK_SIZE else b""
            is_final = not following
            sealed = _seal(key, current, _nonce(salt, index), _aad(index, is_final))
            marker = len(sealed) | (FINAL_FLAG if is_final else 0)
            target.write(struct.pack(">I", marker))
            target.write(sealed)
            if is_final:
                break
            current = following
            index += 1


def _encrypt_blob(key: bytes, payload: bytes) -> bytes:
    """Encrypt small JSON metadata as nonce || AES-GCM ciphertext+tag."""
    nonce = secrets.token_bytes(NONCE_LENGTH)
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encrypted = encryptor.update(payload) + encryptor.finalize()
    return nonce + encrypted + encryptor.tag


def _encode_metadata(key: bytes, metadata: dict[str, Any]) -> str:
    """Return the compact TW1 caption marker understood by the TeleWarden app."""
    raw = json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    blob = _encrypt_blob(key, raw)
    encoded = base64.urlsafe_b64encode(blob).decode("ascii").rstrip("=")
    return "tw1:" + encoded


def _media_kind(message: Message) -> str:
    for name in ("photo", "video", "document", "audio", "voice", "video_note", "animation", "sticker"):
        if getattr(message, name, None) is not None:
            return name
    return "media"


def _media_object(message: Message) -> Any:
    for name in ("photo", "video", "document", "audio", "voice", "video_note", "animation", "sticker"):
        value = getattr(message, name, None)
        if value is not None:
            return value
    return None


def _original_filename(message: Message, path: str) -> str:
    document = getattr(message, "document", None)
    if document and getattr(document, "file_name", None):
        return str(document.file_name)
    video = getattr(message, "video", None)
    if video:
        return f"video_{message.id}.mp4"
    audio = getattr(message, "audio", None)
    if audio:
        return str(getattr(audio, "file_name", None) or f"audio_{message.id}")
    voice = getattr(message, "voice", None)
    if voice:
        return f"voice_{message.id}.ogg"
    video_note = getattr(message, "video_note", None)
    if video_note:
        return f"video_note_{message.id}.mp4"
    photo = getattr(message, "photo", None)
    if photo:
        return f"photo_{message.id}.jpg"
    return Path(path).name or f"media_{message.id}.bin"


def _mime_type(message: Message, filename: str) -> str:
    obj = _media_object(message)
    mime = getattr(obj, "mime_type", None) if obj else None
    return str(mime or mimetypes.guess_type(filename)[0] or "application/octet-stream")


def _sender_metadata(message: Message) -> dict[str, Any]:
    user = message.from_user
    sender_chat = message.sender_chat
    return {
        "id": getattr(user, "id", None),
        "name": getattr(user, "full_name", None),
        "username": getattr(user, "username", None),
        "chat_id": getattr(sender_chat, "id", None),
        "chat_title": getattr(sender_chat, "title", None),
    }


def _media_metadata(message: Message, original_path: str, original_filename: str) -> dict[str, Any]:
    obj = _media_object(message)
    return {
        "protocol": "telewarden",
        "version": 1,
        "record_type": "encrypted_media",
        "source_message_id": message.id,
        "source_chat_id": message.chat.id if message.chat else None,
        "source_thread_id": getattr(message, "message_thread_id", None),
        "source_date": message.date.timestamp() if message.date else None,
        "sender": _sender_metadata(message),
        "media_type": _media_kind(message),
        "original_filename": original_filename,
        "mime_type": _mime_type(message, original_filename),
        "caption": message.caption or "",
        "caption_entities": [entity.__class__.__name__ for entity in (message.caption_entities or [])],
        "size_bytes": os.path.getsize(original_path) if os.path.exists(original_path) else 0,
        "encrypted_filename": True,
        "encrypted_caption": True,
        "envelope": "TDE1",
    }


async def _download_media(client: Client, message: Message, directory: str) -> str:
    os.makedirs(directory, exist_ok=True)
    result = await client.download_media(message, file_name=directory + os.sep)
    if not result or not os.path.isfile(result):
        raise RuntimeError("Telegram did not return a local media file")
    return result


async def _send_encrypted_media(
    client: Client,
    message: Message,
    destination: int,
    thread_id: int | None,
    key: bytes,
    status: Message | None = None,
) -> None:
    with tempfile.TemporaryDirectory(prefix="twlog_") as work:
        plain_path = await _download_media(client, message, work)
        original_filename = _original_filename(message, plain_path)
        metadata = _media_metadata(message, plain_path, original_filename)
        encrypted_path = os.path.join(work, "payload.tde")
        await asyncio.to_thread(_encrypt_stream, plain_path, encrypted_path, key)

        # No original name or caption is visible to Telegram. The app restores both
        # from the encrypted TW1 metadata marker.
        opaque_name = f"tw_{secrets.token_hex(12)}.tde"
        caption = _encode_metadata(key, metadata)
        started = _now()
        await client.send_document(
            chat_id=destination,
            document=encrypted_path,
            file_name=opaque_name,
            caption=caption,
            message_thread_id=thread_id,
            force_document=True,
            progress=_upload_progress if status else None,
            progress_args=(status, "Encrypted media", started) if status else None,
        )


# -----------------------------------------------------------------------------
# History and forum-topic helpers
# -----------------------------------------------------------------------------


async def _get_topic_names(client: Client, chat_id: int) -> dict[int, str]:
    names: dict[int, str] = {}
    try:
        peer = await client.resolve_peer(chat_id)
        offset_id = offset_date = offset_topic = 0
        limit = 100
        while True:
            result = await client.invoke(
                functions.channels.GetForumTopics(
                    channel=peer,
                    q="",
                    offset_date=offset_date,
                    offset_id=offset_id,
                    offset_topic=offset_topic,
                    limit=limit,
                )
            )
            for topic in result.topics:
                names[topic.id] = topic.title
            if len(result.topics) < limit:
                break
            last = result.topics[-1]
            offset_topic, offset_date, offset_id = last.id, last.date, 0
    except Exception:
        pass
    return names


async def _fetch_all(client: Client, src_id: int, thread_id: int | None, status: Message) -> list[Message]:
    messages: list[Message] = []
    scanned = 0

    if thread_id:
        try:
            async for item in client.search_messages(src_id, query="", thread_id=thread_id):
                if _cancel:
                    break
                scanned += 1
                messages.append(item)
                await _progress(status, f"⏳ Fetching… {scanned} scanned, {len(messages)} matched")
            if messages:
                messages.reverse()
                return messages
        except Exception:
            messages.clear()
            scanned = 0

    async for item in client.get_chat_history(src_id):
        if _cancel:
            break
        scanned += 1
        if thread_id and item.message_thread_id != thread_id:
            continue
        messages.append(item)
        await _progress(status, f"⏳ Fetching… {scanned} scanned, {len(messages)} matched")
    messages.reverse()
    return messages


async def _create_topic_map(client: Client, destination: int, names: dict[int, str]) -> dict[int, int]:
    mapping: dict[int, int] = {}
    for source_id, name in names.items():
        if _cancel:
            break
        try:
            created = await client.create_forum_topic(destination, name)
            mapping[source_id] = created.id
        except FloodWait as error:
            _note_flood(error.value)
            await _gate()
            try:
                created = await client.create_forum_topic(destination, name)
                mapping[source_id] = created.id
            except Exception:
                pass
        except Exception:
            # A non-forum destination, or a topic the account cannot create.
            pass
        await asyncio.sleep(0.4)
    return mapping


async def _copy_one_message(
    client: Client,
    message: Message,
    destination: int,
    destination_thread: int | None,
    key: bytes,
    status: Message,
) -> str:
    if _is_service(message):
        return "skipped"

    if message.media:
        await _send_encrypted_media(client, message, destination, destination_thread, key, status)
        return "encrypted"

    # Text, links and other non-media messages stay plaintext by design.
    await message.copy(chat_id=destination, message_thread_id=destination_thread)
    return "plaintext"


# -----------------------------------------------------------------------------
# Automatic mlog-compatible media logging
# -----------------------------------------------------------------------------


def _group_data(chat_id: int) -> dict[str, Any]:
    value = _db_get(str(chat_id), {})
    return value if isinstance(value, dict) else {}


def _save_group_data(chat_id: int, value: dict[str, Any]) -> None:
    _db_set(str(chat_id), value)


async def _ensure_user_topic(client: Client, destination: int, user: Any) -> int | None:
    data = _group_data(destination)
    topics = data.setdefault("user_topics", {})
    key = str(user.id)
    if topics.get(key):
        return int(topics[key])
    try:
        topic = await client.create_forum_topic(destination, user.first_name or str(user.id))
    except Exception:
        return None
    topics[key] = topic.id
    _save_group_data(destination, data)
    info = (
        f"<b>Chat Name:</b> {user.full_name}\n"
        f"<b>User ID:</b> {user.id}\n"
        f"<b>Username:</b> @{user.username or 'N/A'}"
    )
    try:
        await client.send_message(destination, info, message_thread_id=topic.id)
    except Exception:
        pass
    return topic.id


async def _process_user_media(client: Client, user: Any) -> None:
    await asyncio.sleep(5)
    user_id = user.id
    destination = _destination_chat()
    key = _load_key()
    if not destination or not key:
        _media_cache.pop(user_id, None)
        return
    topic_id = await _ensure_user_topic(client, destination, user)
    messages = _media_cache.pop(user_id, [])
    if topic_id is None:
        return
    for item in messages:
        if not _logging_enabled() or _cancel:
            break
        try:
            await _gate()
            await _send_encrypted_media(client, item, destination, topic_id, key)
        except FloodWait as error:
            _note_flood(error.value)
            await _gate()
            try:
                await _send_encrypted_media(client, item, destination, topic_id, key)
            except Exception:
                pass
        except Exception:
            pass
        await asyncio.sleep(SEND_DELAY)
    _media_tasks.pop(user_id, None)


@Client.on_message(
    filters.incoming
    & filters.private
    & (filters.photo | filters.video | filters.document | filters.audio | filters.video_note | filters.voice | filters.animation)
    & ~filters.sticker
    & ~filters.me
    & ~filters.bot
)
async def twlog_media_listener(client: Client, message: Message):
    if not _logging_enabled() or not _load_key() or not message.from_user:
        return
    user_id = message.from_user.id
    _media_cache[user_id].append(message)
    task = _media_tasks.get(user_id)
    if task is None or task.done():
        _media_tasks[user_id] = asyncio.create_task(_process_user_media(client, message.from_user))


# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------


@Client.on_message(filters.command(["twlog"], prefix) & filters.me)
async def twlog_command(_, message: Message):
    args = message.command[1:] if len(message.command) > 1 else []
    if len(args) != 1 or args[0].lower() not in {"on", "off"}:
        return await message.edit(f"<b>Usage:</b> <code>{prefix}twlog on|off</code>")
    enabled = args[0].lower() == "on"
    _db_set("status", enabled)
    await message.edit(f"<b>TeleWarden media logging {'enabled' if enabled else 'disabled'}.</b>")


@Client.on_message(filters.command(["twsetchat"], prefix) & filters.me)
async def twsetchat_command(_, message: Message):
    if len(message.command) != 2:
        return await message.edit(f"<b>Usage:</b> <code>{prefix}twsetchat &lt;forum_chat_id&gt;</code>")
    try:
        chat_id = _norm_id(message.command[1])
    except ValueError:
        return await message.edit("<b>Invalid chat ID.</b>")
    _db_set("chat", chat_id)
    await message.edit(f"<b>Destination forum chat set:</b> <code>{chat_id}</code>")


@Client.on_message(filters.command(["twnewkey"], prefix) & filters.me)
async def twnewkey_command(client: Client, message: Message):
    key = secrets.token_bytes(32)
    _save_key(key)
    try:
        await message.delete()
    except Exception:
        pass
    await client.send_message(
        "me",
        "<b>TeleWarden content key</b>\n"
        "Import this key into TeleWarden. It is not recoverable if lost:\n\n"
        f"<code>{_key_text(key)}</code>",
    )


@Client.on_message(filters.command(["twsetkey"], prefix) & filters.me)
async def twsetkey_command(_, message: Message):
    if len(message.command) != 2:
        return await message.edit(f"<b>Usage:</b> <code>{prefix}twsetkey &lt;base64-key&gt;</code>")
    try:
        encoded = message.command[1]
        key = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        if len(key) != 32:
            raise ValueError
        _save_key(key)
    except Exception:
        try:
            await message.delete()
        except Exception:
            pass
        return await _.send_message("me", "<b>Invalid key. Use a URL-safe base64 32-byte key.</b>")
    try:
        await message.delete()
    except Exception:
        pass
    await _.send_message("me", "<b>TeleWarden key saved.</b>")


@Client.on_message(filters.command(["twcopy"], prefix) & filters.me)
async def twcopy_command(client: Client, message: Message):
    global _running, _cancel

    args = message.command[1:]
    auto_source = message.chat.id
    auto_thread = message.message_thread_id
    if len(args) == 1:
        try:
            source, destination, selected_thread = auto_source, _norm_id(args[0]), auto_thread
        except ValueError:
            return await message.edit("<b>Invalid destination ID.</b>")
    elif len(args) == 2:
        try:
            source, destination, selected_thread = _norm_id(args[0]), _norm_id(args[1]), None
        except ValueError:
            return await message.edit("<b>Invalid chat ID.</b>")
    elif len(args) == 3:
        try:
            source, selected_thread, destination = _norm_id(args[0]), int(args[1]), _norm_id(args[2])
        except ValueError:
            return await message.edit("<b>Invalid chat or topic ID.</b>")
    else:
        return await message.edit(
            f"<b>Usage:</b>\n"
            f"<code>{prefix}twcopy &lt;dst&gt;</code> — current topic → destination\n"
            f"<code>{prefix}twcopy &lt;src&gt; &lt;dst&gt;</code> — full chat\n"
            f"<code>{prefix}twcopy &lt;src&gt; &lt;topic_id&gt; &lt;dst&gt;</code> — one topic"
        )

    if _running:
        return await message.edit(f"<b>A copy job is already running.</b> Use <code>{prefix}twstop</code>.")
    key = _load_key()
    if not key:
        return await message.edit(f"<b>No TeleWarden key configured.</b> Use <code>{prefix}twnewkey</code> first.")

    _running, _cancel = True, False
    status = await message.edit(
        f"⏳ <b>Preparing encrypted copy</b>\n<code>{source}</code> → <code>{destination}</code>"
    )
    run_dir = os.path.join(TEMP_DIR, _ts())
    os.makedirs(run_dir, exist_ok=True)
    copied = encrypted = plaintext = skipped = failed = 0
    last_error = ""

    try:
        await _boost(client)
        await _warm_peer(client, source)
        await _warm_peer(client, destination)
        topic_names = await _get_topic_names(client, source)
        if selected_thread:
            source_topic_name = topic_names.get(selected_thread, f"Thread {selected_thread}")
            destination_topics = await _create_topic_map(client, destination, {selected_thread: source_topic_name})
        else:
            destination_topics = await _create_topic_map(client, destination, topic_names)

        messages = await _fetch_all(client, source, selected_thread, status)
        if not messages:
            return await status.edit("⚠️ No messages found.")

        await status.edit(
            f"📋 <b>{len(messages)} messages found.</b>\n"
            f"Encrypted media and plaintext text will be copied.\n"
            f"Use <code>{prefix}twstop</code> to cancel."
        )

        for index, item in enumerate(messages, 1):
            if _cancel:
                break
            destination_thread = destination_topics.get(item.message_thread_id)
            try:
                await _gate()
                result = await _copy_one_message(client, item, destination, destination_thread, key, status)
                copied += 1
                if result == "encrypted":
                    encrypted += 1
                elif result == "plaintext":
                    plaintext += 1
                else:
                    skipped += 1
            except FloodWait as error:
                _note_flood(error.value)
                await _gate()
                try:
                    result = await _copy_one_message(client, item, destination, destination_thread, key, status)
                    copied += 1
                    if result == "encrypted":
                        encrypted += 1
                    elif result == "plaintext":
                        plaintext += 1
                except Exception as retry_error:
                    failed += 1
                    last_error = str(retry_error)[:120]
            except Exception as error:
                failed += 1
                last_error = str(error)[:120]

            await _progress(
                status,
                f"⏳ {index}/{len(messages)} — ✅ {copied} copied "
                f"(🔒 {encrypted}, 📝 {plaintext}) | ❌ {failed}",
                force=index == len(messages),
            )
            await asyncio.sleep(SEND_DELAY)

        result_text = "🛑 Cancelled" if _cancel else "✅ Finished"
        await status.edit(
            f"<b>{result_text}</b>\n\n"
            f"Total: {len(messages)}\n"
            f"Copied: {copied}\n"
            f"Encrypted media: {encrypted}\n"
            f"Plaintext messages: {plaintext}\n"
            f"Skipped service messages: {skipped}\n"
            f"Failed: {failed}"
            + (f"\nLast error: <code>{last_error}</code>" if last_error else "")
        )
    except Exception as error:
        await status.edit(f"❌ <b>Copy failed:</b>\n<code>{format_exc(error)}</code>")
    finally:
        shutil.rmtree(run_dir, ignore_errors=True)
        _running, _cancel = False, False


@Client.on_message(filters.command(["twstop"], prefix) & filters.me)
async def twstop_command(_, message: Message):
    global _cancel
    if not _running:
        return await message.edit("<b>No TeleWarden copy job is running.</b>")
    _cancel = True
    await message.edit("<b>Stop signal sent. The current message will finish, then the job will stop.</b>")


@Client.on_message(filters.command(["twstatus"], prefix) & filters.me)
async def twstatus_command(_, message: Message):
    key = _load_key()
    await message.edit(
        "<b>TeleWarden twlog status</b>\n\n"
        f"Logging: <code>{'on' if _logging_enabled() else 'off'}</code>\n"
        f"Destination: <code>{_destination_chat() or 'not set'}</code>\n"
        f"Key: <code>{'configured' if key else 'not configured'}</code>\n"
        f"Copy job: <code>{'running' if _running else 'idle'}</code>"
    )


modules_help["twlog"] = {
    "twlog [on/off]": "Enable or disable encrypted private-media logging",
    "twsetchat [chat_id]": "Set the destination forum group for automatic logging",
    "twnewkey": "Generate a TeleWarden AES-256 key and send it to Saved Messages",
    "twsetkey [base64_key]": "Set an existing TeleWarden base64 key",
    "twcopy [dst]": "Copy the current topic to dst with encrypted media",
    "twcopy [src] [dst]": "Copy a full group/channel with encrypted media",
    "twcopy [src] [topic_id] [dst]": "Copy one forum topic with encrypted media",
    "twstop": "Cancel the running TeleWarden copy job",
    "twstatus": "Show twlog configuration and job status",
}
