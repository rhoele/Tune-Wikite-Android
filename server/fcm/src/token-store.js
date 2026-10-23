'use strict';

const fs = require('fs');
const path = require('path');

/**
 * 设备令牌存储。
 *
 * 默认落盘为单个 JSON 文件，适合单实例小规模部署：
 *   - 写入使用「临时文件 + rename」保证原子性，避免进程中断留下半个文件；
 *   - 读取失败（文件缺失 / 损坏）时退化为空表，不让服务启动失败。
 *
 * 生产环境（多实例 / 高并发）请替换为 Redis 或数据库，接口保持不变即可。
 */
class TokenStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.tokens = new Map();
    this.load();
  }

  load() {
    try {
      if (!fs.existsSync(this.filePath)) {
        return;
      }
      const raw = fs.readFileSync(this.filePath, 'utf8');
      const parsed = JSON.parse(raw);
      const list = Array.isArray(parsed) ? parsed : parsed.tokens;
      if (Array.isArray(list)) {
        for (const item of list) {
          if (item && typeof item.token === 'string' && item.token) {
            this.tokens.set(item.token, item);
          }
        }
      }
    } catch (err) {
      console.warn('[token-store] 读取失败，按空表处理:', err.message);
      this.tokens = new Map();
    }
  }

  save() {
    const dir = path.dirname(this.filePath);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = `${this.filePath}.${process.pid}.tmp`;
    const payload = JSON.stringify({ tokens: [...this.tokens.values()] }, null, 2);
    fs.writeFileSync(tmp, payload, 'utf8');
    fs.renameSync(tmp, this.filePath);
  }

  upsert(record) {
    const token = record.token;
    if (!token) {
      return null;
    }
    const existing = this.tokens.get(token) || {};
    const merged = {
      ...existing,
      ...record,
      token,
      updatedAt: new Date().toISOString(),
      createdAt: existing.createdAt || new Date().toISOString(),
    };
    this.tokens.set(token, merged);
    this.save();
    return merged;
  }

  remove(token) {
    if (!token) {
      return false;
    }
    const removed = this.tokens.delete(token);
    if (removed) {
      this.save();
    }
    return removed;
  }

  has(token) {
    return Boolean(token) && this.tokens.has(token);
  }

  all() {
    return [...this.tokens.values()];
  }

  byUser(userId) {
    if (userId === undefined || userId === null || userId === '') {
      return [];
    }
    return this.all().filter((item) => String(item.userId) === String(userId));
  }

  size() {
    return this.tokens.size;
  }
}

module.exports = { TokenStore };
