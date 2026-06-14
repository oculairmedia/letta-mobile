import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

function b64url(value) {
  return Buffer.from(value).toString('base64url');
}

function storageDir() {
  return process.env.LETTA_LOCAL_BACKEND_DIR || join(process.env.LETTA_HOME || join(homedir(), '.letta'), 'lc-local-backend');
}

function readJsonOrNull(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return null;
  }
}

export function getAgentRecord(agentId) {
  if (!agentId) return null;
  const path = join(storageDir(), 'agents', `${b64url(agentId)}.json`);
  const record = existsSync(path) ? readJsonOrNull(path) : null;
  return record && typeof record === 'object' && typeof record.id === 'string' ? record : null;
}

export function readSystemPrompt(conversationId, agentId) {
  if (!agentId) return null;
  const diskConversationId = conversationId || 'default';
  const key = diskConversationId === 'default' ? `default:${agentId}` : `conversation:${diskConversationId}`;
  const path = join(storageDir(), 'conversations', b64url(key), 'system-prompt.json');
  const prompt = existsSync(path) ? readJsonOrNull(path) : null;
  return prompt && typeof prompt === 'object' ? prompt : null;
}
