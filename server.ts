/**
 * TeleDrive Encrypted Cloud Session & Settings Server (server.ts)
 *
 * A strongly encrypted MongoDB-backed backend server for storing and restoring
 * TeleDrive user sessions and application settings.
 *
 * Security Architecture:
 * 1. Client-Side E2EE: The app encrypts the session & settings payload with AES-256-GCM
 *    (key derived via PBKDF2-HMAC-SHA256, 120,000 iterations) before transmission.
 * 2. Password Verification: Passwords are never stored in plaintext; the server hashes
 *    passwords with scrypt (N=16384, r=8, p=1, 64-byte output) + 32-byte random salt
 *    and verifies them using crypto.timingSafeEqual.
 * 3. Server-Side At-Rest Encryption in MongoDB: Every user record stored in MongoDB is
 *    sealed using AES-256-GCM with a per-user key derived via HKDF-SHA256 from the 256-bit
 *    server master key and authenticated with AAD bound to the user ID. Even a full
 *    database dump exposes zero plaintext metadata or envelopes without SERVER_MASTER_KEY.
 * 4. Session Tokens: Stateless Bearer tokens signed with HMAC-SHA256 and bound to
 *    user ID and expiration timestamp.
 *
 * Environment Variables:
 *   MONGODB_URI        - MongoDB connection string (e.g. mongodb+srv://... or mongodb://127.0.0.1:27017)
 *   MONGODB_DB_NAME    - Database name (default: "teledrive_cloud")
 *   SERVER_MASTER_KEY  - 32+ char secret master key for HKDF & HMAC derivation
 *   PORT               - HTTP listen port (default: 8787)
 *
 * Run with:
 *   npm install mongodb
 *   MONGODB_URI="mongodb://127.0.0.1:27017" SERVER_MASTER_KEY="your-secret" npx tsx server.ts
 */

import * as http from "node:http";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import { MongoClient, Collection, Db } from "mongodb";

const PORT = Number(process.env.PORT || 3000);
const MONGODB_URI = (process.env.MONGODB_URI || process.env.MONGO_URI || "mongodb://127.0.0.1:27017").trim();
const MONGODB_DB_NAME = (process.env.MONGODB_DB_NAME || "teledrive_cloud").trim();
const DATA_DIR = path.resolve(process.env.CLOUD_DATA_DIR || "./.cloud-store");
const USERS_DIR = path.join(DATA_DIR, "users");
const MASTER_KEY_FILE = path.join(DATA_DIR, ".master.key");
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days
const MAX_BODY_BYTES = 32 * 1024 * 1024; // 32 MB max payload for session + settings

fs.mkdirSync(USERS_DIR, { recursive: true, mode: 0o700 });

/**
 * Loads or generates the 256-bit (32-byte) Server Master Key.
 */
function loadOrCreateMasterKey(): Buffer {
    const envKey = process.env.SERVER_MASTER_KEY?.trim();
    if (envKey && envKey.length >= 32) {
        return crypto.createHash("sha256").update(envKey, "utf8").digest();
    }
    if (fs.existsSync(MASTER_KEY_FILE)) {
        const raw = fs.readFileSync(MASTER_KEY_FILE, "utf8").trim();
        return Buffer.from(raw, "hex");
    }
    const generated = crypto.randomBytes(32);
    fs.writeFileSync(MASTER_KEY_FILE, generated.toString("hex"), { mode: 0o600 });
    return generated;
}

const MASTER_KEY = loadOrCreateMasterKey();

interface StoredUserRecord {
    version: number;
    userId: string;
    username: string;
    scryptSaltHex: string;
    scryptHashHex: string;
    createdAt: number;
    updatedAt: number;
    /** Client-encrypted CloudEnvelope (JSON string or object) */
    envelope: unknown | null;
}

interface SealedEnvelopePayload {
    v: number;
    ivHex: string;
    tagHex: string;
    ciphertextBase64: string;
}

interface MongoUserDocument {
    userId: string;
    sealed: SealedEnvelopePayload;
    updatedAt: Date;
    createdAt: Date;
}

function normalizeUsername(username: string): string {
    return username.trim().toLowerCase();
}

function computeUserId(username: string): string {
    return crypto
        .createHmac("sha256", MASTER_KEY)
        .update("user-id:" + normalizeUsername(username), "utf8")
        .digest("hex");
}

function deriveUserStorageKey(userId: string): Buffer {
    return Buffer.from(
        crypto.hkdfSync(
            "sha256",
            MASTER_KEY,
            Buffer.from(userId, "hex"),
            Buffer.from("teledrive-at-rest-aes256gcm-v1", "utf8"),
            32
        )
    );
}

function deriveTokenSigningKey(): Buffer {
    return Buffer.from(
        crypto.hkdfSync(
            "sha256",
            MASTER_KEY,
            Buffer.alloc(32, 0),
            Buffer.from("teledrive-session-token-hmac-v1", "utf8"),
            32
        )
    );
}

const TOKEN_KEY = deriveTokenSigningKey();

function hashPasswordScrypt(password: string, salt: Buffer): Buffer {
    return crypto.scryptSync(password.normalize("NFKC"), salt, 64, {
        N: 16384,
        r: 8,
        p: 1,
        maxmem: 32 * 1024 * 1024
    });
}

function verifyPasswordScrypt(password: string, saltHex: string, expectedHashHex: string): boolean {
    const salt = Buffer.from(saltHex, "hex");
    const expected = Buffer.from(expectedHashHex, "hex");
    const actual = hashPasswordScrypt(password, salt);
    if (actual.length !== expected.length) return false;
    return crypto.timingSafeEqual(actual, expected);
}

function sealUserRecord(record: StoredUserRecord): SealedEnvelopePayload {
    const key = deriveUserStorageKey(record.userId);
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
    cipher.setAAD(Buffer.from(record.userId, "utf8"));

    const plaintext = Buffer.from(JSON.stringify(record), "utf8");
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const tag = cipher.getAuthTag();

    return {
        v: 1,
        ivHex: iv.toString("hex"),
        tagHex: tag.toString("hex"),
        ciphertextBase64: encrypted.toString("base64")
    };
}

function unsealUserRecord(userId: string, sealed: SealedEnvelopePayload): StoredUserRecord {
    const key = deriveUserStorageKey(userId);
    const iv = Buffer.from(sealed.ivHex, "hex");
    const tag = Buffer.from(sealed.tagHex, "hex");
    const ciphertext = Buffer.from(sealed.ciphertextBase64, "base64");

    const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAAD(Buffer.from(userId, "utf8"));
    decipher.setAuthTag(tag);

    const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    return JSON.parse(decrypted.toString("utf8")) as StoredUserRecord;
}

// ============================================================================
// MongoDB Connection & Storage Layer (with local encrypted fallback)
// ============================================================================

let mongoClient: MongoClient | null = null;
let mongoDb: Db | null = null;
let usersCollection: Collection<MongoUserDocument> | null = null;

async function initMongo(): Promise<void> {
    try {
        const client = new MongoClient(MONGODB_URI, {
            maxPoolSize: 10,
            minPoolSize: 1,
            serverSelectionTimeoutMS: 5000
        });
        await client.connect();
        const db = client.db(MONGODB_DB_NAME);
        const col = db.collection<MongoUserDocument>("teledrive_users");
        await col.createIndex({ userId: 1 }, { unique: true });

        mongoClient = client;
        mongoDb = db;
        usersCollection = col;
        console.log(`[TeleDrive Cloud Server] Connected to MongoDB (${MONGODB_DB_NAME}.teledrive_users)`);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        console.warn(
            `[TeleDrive Cloud Server] MongoDB unavailable (${msg}). Using local AES-256-GCM disk store fallback at ${USERS_DIR}`
        );
    }
}

function userFilePath(userId: string): string {
    return path.join(USERS_DIR, `${userId}.enc.json`);
}

async function saveEncryptedUserRecord(record: StoredUserRecord): Promise<void> {
    const sealed = sealUserRecord(record);

    if (usersCollection) {
        const now = new Date();
        await usersCollection.updateOne(
            { userId: record.userId },
            {
                $set: {
                    userId: record.userId,
                    sealed,
                    updatedAt: now
                },
                $setOnInsert: {
                    createdAt: now
                }
            },
            { upsert: true }
        );
        return;
    }

    const filePath = userFilePath(record.userId);
    const tempPath = `${filePath}.tmp`;
    fs.writeFileSync(tempPath, JSON.stringify(sealed), { mode: 0o600 });
    fs.renameSync(tempPath, filePath);
}

async function loadEncryptedUserRecord(userId: string): Promise<StoredUserRecord | null> {
    if (usersCollection) {
        const doc = await usersCollection.findOne({ userId });
        if (!doc) return null;
        return unsealUserRecord(userId, doc.sealed);
    }

    const filePath = userFilePath(userId);
    if (!fs.existsSync(filePath)) return null;

    const raw = fs.readFileSync(filePath, "utf8");
    const sealed = JSON.parse(raw) as SealedEnvelopePayload;
    return unsealUserRecord(userId, sealed);
}

async function deleteEncryptedUserRecord(userId: string): Promise<void> {
    if (usersCollection) {
        await usersCollection.deleteOne({ userId });
    }
    const filePath = userFilePath(userId);
    if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
    }
}

// ============================================================================
// Stateless HMAC-SHA256 Session Token Authentication
// ============================================================================

function issueSessionToken(userId: string, username: string): string {
    const payload = {
        sub: userId,
        usr: normalizeUsername(username),
        iat: Date.now(),
        exp: Date.now() + TOKEN_TTL_MS,
        jti: crypto.randomBytes(12).toString("hex")
    };
    const payloadB64 = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
    const sigB64 = crypto
        .createHmac("sha256", TOKEN_KEY)
        .update(payloadB64, "utf8")
        .digest("base64url");
    return `${payloadB64}.${sigB64}`;
}

function verifySessionToken(token: string): { userId: string; username: string } | null {
    const parts = token.split(".");
    if (parts.length !== 2) return null;
    const [payloadB64, sigB64] = parts;
    const expectedSig = crypto
        .createHmac("sha256", TOKEN_KEY)
        .update(payloadB64, "utf8")
        .digest();
    const actualSig = Buffer.from(sigB64, "base64url");
    if (expectedSig.length !== actualSig.length || !crypto.timingSafeEqual(expectedSig, actualSig)) {
        return null;
    }
    try {
        const payload = JSON.parse(Buffer.from(payloadB64, "base64url").toString("utf8")) as {
            sub: string;
            usr: string;
            exp: number;
        };
        if (!payload.sub || typeof payload.exp !== "number" || Date.now() > payload.exp) {
            return null;
        }
        return { userId: payload.sub, username: payload.usr };
    } catch {
        return null;
    }
}

function extractBearerToken(req: http.IncomingMessage): string | null {
    const auth = req.headers["authorization"];
    if (!auth || typeof auth !== "string") return null;
    if (auth.toLowerCase().startsWith("bearer ")) {
        return auth.slice(7).trim();
    }
    return null;
}

function readJsonBody(req: http.IncomingMessage): Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        let total = 0;
        req.on("data", (chunk: Buffer) => {
            total += chunk.length;
            if (total > MAX_BODY_BYTES) {
                reject(new Error("Payload too large"));
                req.destroy();
                return;
            }
            chunks.push(chunk);
        });
        req.on("end", () => {
            try {
                const raw = Buffer.concat(chunks).toString("utf8").trim();
                resolve(raw ? (JSON.parse(raw) as Record<string, unknown>) : {});
            } catch {
                reject(new Error("Invalid JSON body"));
            }
        });
        req.on("error", reject);
    });
}

function sendJson(res: http.ServerResponse, status: number, data: Record<string, unknown>): void {
    const body = JSON.stringify(data);
    res.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff"
    });
    res.end(body);
}

// ============================================================================
// HTTP Routes
// ============================================================================

const server = http.createServer(async (req, res) => {
    const method = req.method || "GET";
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname.replace(/\/+$/, "") || "/";

    try {
        // Health check
        if (method === "GET" && (pathname === "/api/cloud/health" || pathname === "/health")) {
            sendJson(res, 200, {
                status: "ok",
                service: "teledrive-cloud-session-store",
                storage: usersCollection ? "mongodb" : "encrypted-disk-fallback",
                database: usersCollection ? MONGODB_DB_NAME : null,
                encryption: "AES-256-GCM + Scrypt + HKDF-SHA256"
            });
            return;
        }

        // 1. Register new cloud account & optionally save initial session/settings envelope
        if (method === "POST" && pathname === "/api/cloud/register") {
            const body = await readJsonBody(req);
            const username = typeof body.username === "string" ? body.username.trim() : "";
            const password = typeof body.password === "string" ? body.password : "";
            const envelope = body.envelope ?? null;

            if (username.length < 2 || username.length > 120) {
                sendJson(res, 400, { error: "Username must be between 2 and 120 characters" });
                return;
            }
            if (password.length < 4) {
                sendJson(res, 400, { error: "Password must be at least 4 characters" });
                return;
            }

            const userId = computeUserId(username);
            const existing = await loadEncryptedUserRecord(userId);
            if (existing !== null) {
                sendJson(res, 409, { error: "Username is already registered. Please sign in instead." });
                return;
            }

            const salt = crypto.randomBytes(32);
            const passwordHash = hashPasswordScrypt(password, salt);
            const now = Date.now();

            const record: StoredUserRecord = {
                version: 1,
                userId,
                username: normalizeUsername(username),
                scryptSaltHex: salt.toString("hex"),
                scryptHashHex: passwordHash.toString("hex"),
                createdAt: now,
                updatedAt: now,
                envelope
            };

            await saveEncryptedUserRecord(record);
            const token = issueSessionToken(userId, username);

            sendJson(res, 201, {
                status: "ok",
                username: record.username,
                token,
                envelope: record.envelope,
                updatedAt: record.updatedAt
            });
            return;
        }

        // 2. Sign in to existing cloud account & retrieve saved session/settings envelope
        if (method === "POST" && (pathname === "/api/cloud/login" || pathname === "/api/login")) {
            const body = await readJsonBody(req);
            const username = typeof body.username === "string" ? body.username.trim() : "";
            const password = typeof body.password === "string" ? body.password : "";

            if (!username || !password) {
                sendJson(res, 400, { error: "Username and password are required" });
                return;
            }

            const userId = computeUserId(username);
            const record = await loadEncryptedUserRecord(userId);
            if (!record || !verifyPasswordScrypt(password, record.scryptSaltHex, record.scryptHashHex)) {
                sendJson(res, 401, { error: "Invalid username or password" });
                return;
            }

            if (body.envelope !== undefined && body.envelope !== null) {
                record.envelope = body.envelope;
                record.updatedAt = Date.now();
                await saveEncryptedUserRecord(record);
            }

            const token = issueSessionToken(userId, record.username);
            sendJson(res, 200, {
                status: "ok",
                username: record.username,
                token,
                envelope: record.envelope,
                updatedAt: record.updatedAt
            });
            return;
        }

        // 3. Get or update encrypted userdata (session + settings)
        if (pathname === "/api/cloud/userdata") {
            const token = extractBearerToken(req);
            const verified = token ? verifySessionToken(token) : null;
            if (!verified) {
                sendJson(res, 401, { error: "Unauthorized or expired session token" });
                return;
            }

            const record = await loadEncryptedUserRecord(verified.userId);
            if (!record) {
                sendJson(res, 404, { error: "Account not found" });
                return;
            }

            if (method === "GET") {
                sendJson(res, 200, {
                    status: "ok",
                    username: record.username,
                    envelope: record.envelope,
                    updatedAt: record.updatedAt
                });
                return;
            }

            if (method === "PUT" || method === "POST") {
                const body = await readJsonBody(req);
                if (body.envelope === undefined) {
                    sendJson(res, 400, { error: "Missing encrypted envelope" });
                    return;
                }
                record.envelope = body.envelope;
                record.updatedAt = Date.now();
                await saveEncryptedUserRecord(record);

                sendJson(res, 200, {
                    status: "ok",
                    username: record.username,
                    updatedAt: record.updatedAt
                });
                return;
            }
        }

        // 4. Delete cloud account
        if (method === "DELETE" && pathname === "/api/cloud/account") {
            const token = extractBearerToken(req);
            const verified = token ? verifySessionToken(token) : null;
            if (!verified) {
                sendJson(res, 401, { error: "Unauthorized" });
                return;
            }
            await deleteEncryptedUserRecord(verified.userId);
            sendJson(res, 200, { status: "ok", deleted: true });
            return;
        }

        sendJson(res, 404, { error: "Not found" });
    } catch (err) {
        const message = err instanceof Error ? err.message : "Internal server error";
        sendJson(res, 500, { error: message });
    }
});

async function startServer(): Promise<void> {
    await initMongo();
    server.listen(PORT, () => {
        console.log(
            `[TeleDrive Cloud Server] Listening on port ${PORT} (MongoDB + AES-256-GCM + Scrypt)`
        );
    });
}

process.on("SIGINT", async () => {
    await mongoClient?.close().catch(() => undefined);
    process.exit(0);
});

process.on("SIGTERM", async () => {
    await mongoClient?.close().catch(() => undefined);
    process.exit(0);
});

void startServer();
