require('dotenv').config();

const mineflayer = require('mineflayer');
const net = require('node:net');
const dns = require('node:dns');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { SocksClient } = require('socks');

const dnsServers = (process.env.MC_DNS_SERVERS || '223.5.5.5,119.29.29.29,8.8.8.8')
  .split(',')
  .map((server) => server.trim())
  .filter(Boolean);

if (dnsServers.length > 0) {
  dns.setServers(dnsServers);
}

const config = {
  host: process.env.MC_HOST || 'ranmc.cc',
  port: Number(process.env.MC_PORT || 25565),
  username: process.env.MC_USERNAME || '',
  password: process.env.MC_PASSWORD || '',
  auth: process.env.MC_AUTH || 'offline',
  version: process.env.MC_VERSION || '1.21.11',
  autoAuth: process.env.MC_AUTO_AUTH !== 'false',
  headless: process.env.MC_HEADLESS === 'true',
  registerCommand: process.env.MC_REGISTER_COMMAND || '/reg',
  loginCommand: process.env.MC_LOGIN_COMMAND || '/login',
  checkTimeoutInterval: Number(process.env.MC_CHECK_TIMEOUT_MS || 120000),
  reconnectDelayMs: Number(process.env.MC_RECONNECT_DELAY_MS || 5000),
  proxyType: (process.env.MC_PROXY_TYPE || 'socks5').toLowerCase(),
  proxyHost: process.env.MC_PROXY_HOST || '127.0.0.1',
  proxyPort: Number(process.env.MC_PROXY_PORT || 7897),
  dnsServers
};

let bot;
let reconnectTimer;
let intentionallyClosed = false;
let session = createSession();
let authState = {
  registerSent: false,
  loginSent: false
};

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  prompt: '> '
});

function log(message) {
  process.stdout.write(`\n${message}\n`);
  if (!config.headless) {
    rl.prompt(true);
  }
}

function mask(value) {
  if (!value) return '(empty)';
  if (value.length <= 2) return '*'.repeat(value.length);
  return `${value[0]}${'*'.repeat(value.length - 2)}${value[value.length - 1]}`;
}

function randomText(prefix, bytes) {
  return `${prefix}${crypto.randomBytes(bytes).toString('hex')}`;
}

function randomAlphaNum(length) {
  const letters = 'abcdefghijklmnopqrstuvwxyz';
  const digits = '0123456789';
  const alphabet = letters + digits;
  let value = '';

  while (value.length < length) {
    const bytes = crypto.randomBytes(length);
    for (const byte of bytes) {
      if (value.length >= length) break;
      value += alphabet[byte % alphabet.length];
    }
  }

  if (!/[a-z]/.test(value)) {
    value = `${letters[crypto.randomInt(letters.length)]}${value.slice(1)}`;
  }

  if (!/[0-9]/.test(value)) {
    value = `${value.slice(0, -1)}${digits[crypto.randomInt(digits.length)]}`;
  }

  return value;
}

function randomUsername() {
  const words = [
    'Alex', 'Steve', 'Mango', 'River', 'Cloud', 'Maple', 'Nova', 'Pixel',
    'Sunny', 'Luna', 'Robin', 'Kevin', 'Jason', 'Henry', 'Alice', 'Cindy',
    'Cherry', 'Forest', 'Winter', 'Summer', 'Orange', 'Coffee', 'Moon', 'Star'
  ];
  const word = words[crypto.randomInt(words.length)];
  const number = crypto.randomInt(100, 9999);
  return `${word}${number}`.slice(0, 16);
}

function createSession() {
  return {
    username: config.username || randomUsername(),
    password: config.password || randomAlphaNum(12)
  };
}

function normalizeMessage(message) {
  if (!message) return '';
  if (typeof message === 'string') return message;
  if (typeof message.toAnsi === 'function') return message.toAnsi();
  if (typeof message.toString === 'function') return message.toString();
  return String(message);
}

function sendChat(text) {
  if (!bot || !bot.entity) {
    log('[bot] 还没进服，命令没有发送。');
    return;
  }

  bot.chat(text);
}

function sendRegister() {
  if (!session.password || authState.registerSent) return;
  authState.registerSent = true;
  sendChat(`${config.registerCommand} ${session.password} ${session.password}`);
  log(`[auth] 已发送 ${config.registerCommand}。`);
}

function sendLogin() {
  if (!session.password || authState.loginSent) return;
  authState.loginSent = true;
  sendChat(`${config.loginCommand} ${session.password}`);
  log(`[auth] 已发送 ${config.loginCommand}。`);
}

function maybeAuthFromMessage(rawMessage) {
  if (!config.autoAuth || !session.password) return;

  const message = rawMessage.toLowerCase();
  if (
    message.includes('/register') ||
    message.includes('/reg') ||
    message.includes('register') ||
    message.includes('reg ') ||
    message.includes('注册') ||
    message.includes('未注册')
  ) {
    setTimeout(sendRegister, 500);
    return;
  }

  if (message.includes('/login') || message.includes('login') || message.includes('登录') || message.includes('登陆')) {
    setTimeout(sendLogin, 500);
  }
}

function resolveMinecraftTarget() {
  return new Promise((resolve) => {
    if (config.port !== 25565 || net.isIP(config.host) !== 0 || config.host === 'localhost') {
      resolve({ host: config.host, port: config.port, serverHost: config.host, serverPort: config.port });
      return;
    }

    dns.resolveSrv(`_minecraft._tcp.${config.host}`, (error, records) => {
      if (error || !records || records.length === 0) {
        resolve({ host: config.host, port: config.port, serverHost: config.host, serverPort: config.port });
        return;
      }

      const record = records.sort((a, b) => a.priority - b.priority || b.weight - a.weight)[0];
      resolve({ host: record.name, port: record.port, serverHost: config.host, serverPort: config.port });
    });
  });
}

function connectViaSocks5(client) {
  resolveMinecraftTarget()
    .then((target) => {
      SocksClient.createConnection({
        proxy: {
          host: config.proxyHost,
          port: config.proxyPort,
          type: 5
        },
        command: 'connect',
        destination: {
          host: target.host,
          port: target.port
        }
      }, (error, info) => {
        if (error) {
          client.emit('error', error);
          return;
        }

        client.setSocket(info.socket);
        client.emit('connect');
      });
    })
    .catch((error) => {
      client.emit('error', error);
    });
}

function connectViaHttpProxy(client) {
  resolveMinecraftTarget()
    .then((target) => {
      const socket = net.connect(config.proxyPort, config.proxyHost);

      socket.once('connect', () => {
        socket.write([
          `CONNECT ${target.host}:${target.port} HTTP/1.1`,
          `Host: ${target.host}:${target.port}`,
          'Proxy-Connection: Keep-Alive',
          '',
          ''
        ].join('\r\n'));
      });

      socket.once('error', (error) => {
        client.emit('error', error);
      });

      let header = Buffer.alloc(0);
      const onData = (chunk) => {
        header = Buffer.concat([header, chunk]);
        const headerEnd = header.indexOf('\r\n\r\n');
        if (headerEnd === -1) return;

        const response = header.toString('utf8', 0, headerEnd);
        if (!response.startsWith('HTTP/1.1 200') && !response.startsWith('HTTP/1.0 200')) {
          socket.destroy();
          client.emit('error', new Error(`HTTP proxy CONNECT failed: ${response.split('\r\n')[0]}`));
          return;
        }

        socket.off('data', onData);
        const rest = header.subarray(headerEnd + 4);
        if (rest.length > 0) socket.unshift(rest);
        client.setSocket(socket);
        client.emit('connect');
      };

      socket.on('data', onData);
    })
    .catch((error) => {
      client.emit('error', error);
    });
}

function applyProxy(botOptions) {
  if (config.proxyType === 'none' || config.proxyType === 'direct') return;

  if (config.proxyType === 'socks5') {
    botOptions.connect = connectViaSocks5;
    return;
  }

  if (config.proxyType === 'http') {
    botOptions.connect = connectViaHttpProxy;
    return;
  }

  throw new Error(`不支持的 MC_PROXY_TYPE=${config.proxyType}，可用 socks5/http/none。`);
}

function connect() {
  clearTimeout(reconnectTimer);
  authState = {
    registerSent: false,
    loginSent: false
  };

  const botOptions = {
    host: config.host,
    port: config.port,
    username: session.username,
    auth: config.auth,
    checkTimeoutInterval: config.checkTimeoutInterval
  };

  if (config.version) {
    botOptions.version = config.version;
  }

  try {
    applyProxy(botOptions);
  } catch (error) {
    log(`[bot] 配置错误：${error.message}`);
    reconnectTimer = setTimeout(connect, config.reconnectDelayMs);
    return;
  }

  const proxyText = config.proxyType === 'none' || config.proxyType === 'direct'
    ? '直连'
    : `${config.proxyType}://${config.proxyHost}:${config.proxyPort}`;
  log(`[bot] 正在连接 ${config.host}:${config.port}，昵称 ${session.username}，密码 ${mask(session.password)}，代理 ${proxyText}，auth=${config.auth}。`);
  bot = mineflayer.createBot(botOptions);

  bot.once('spawn', () => {
    log('[bot] 已进服。终端输入内容后回车即可发送到服务器，/ 开头会作为服务器命令发送。');

    if (config.autoAuth && !session.password) {
      log('[auth] 未设置 MC_PASSWORD，跳过自动注册/登录。');
    }
  });

  bot.on('message', (message) => {
    const text = normalizeMessage(message);
    log(`[mc] ${text}`);
    maybeAuthFromMessage(text);
  });

  bot.on('kicked', (reason) => {
    log(`[bot] 被踢出：${normalizeMessage(reason)}`);
  });

  bot.on('error', (error) => {
    log(`[bot] 错误：${error.message}`);
  });

  bot.on('end', () => {
    log('[bot] 连接已断开。');

    if (!intentionallyClosed) {
      reconnectTimer = setTimeout(connect, config.reconnectDelayMs);
      log(`[bot] ${Math.round(config.reconnectDelayMs / 1000)} 秒后自动重连，继续使用昵称 ${session.username}。`);
    }
  });
}

function printHelp() {
  log([
    '[help] 可用终端命令：',
    '  .help       显示帮助',
    '  .status     显示当前连接状态',
    '  .password   显示本次运行的昵称和完整密码',
    '  .reconnect  立即重连',
    '  .quit       退出机器人',
    '其他任何内容都会原样发送到 Minecraft；例如 /spawn 或 /msg 玩家 内容。'
  ].join('\n'));
}

rl.on('line', (line) => {
  const input = line.trim();
  if (!input) {
    rl.prompt();
    return;
  }

  if (input === '.help') {
    printHelp();
    return;
  }

  if (input === '.status') {
    const state = bot?.entity ? 'online' : 'offline';
    log(`[bot] state=${state}, host=${config.host}:${config.port}, username=${session.username}, password=${mask(session.password)}, proxy=${config.proxyType}://${config.proxyHost}:${config.proxyPort}`);
    return;
  }

  if (input === '.password') {
    log(`[bot] username=${session.username}, password=${session.password}`);
    return;
  }

  if (input === '.reconnect') {
    clearTimeout(reconnectTimer);
    if (bot) {
      intentionallyClosed = true;
      bot.once('end', () => {
        intentionallyClosed = false;
        connect();
      });
      bot.end();
    } else {
      intentionallyClosed = false;
      connect();
    }
    return;
  }

  if (input === '.quit') {
    intentionallyClosed = true;
    clearTimeout(reconnectTimer);
    rl.close();
    if (bot) bot.end();
    return;
  }

  sendChat(input);
});

rl.on('close', () => {
  intentionallyClosed = true;
  clearTimeout(reconnectTimer);
  if (!config.headless) {
    process.exit(0);
  }
});

process.on('SIGINT', () => {
  intentionallyClosed = true;
  clearTimeout(reconnectTimer);
  if (bot) bot.end();
  rl.close();
});

log(`[config] MC_HOST=${config.host}, MC_PORT=${config.port}, MC_PROXY_TYPE=${config.proxyType}, MC_PROXY=${config.proxyHost}:${config.proxyPort}, MC_DNS_SERVERS=${config.dnsServers.join(',') || '(system)'}`);
connect();
rl.prompt();
