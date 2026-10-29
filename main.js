// VENSER для Windows: окно сайта с системным запретом захвата экрана + автообновление.
const { app, BrowserWindow, shell, Menu, session, dialog } = require('electron');
const path = require('path');
let autoUpdater = null;
try { autoUpdater = require('electron-updater').autoUpdater; } catch (e) { autoUpdater = null; }

const START = 'https://bazznania.web.app/';
const ALLOWED = ['bazznania.web.app', 'bazznania.firebaseapp.com'];
const isAllowed = u => { try { return ALLOWED.includes(new URL(u).hostname); } catch (e) { return false; } };

if (!app.requestSingleInstanceLock()) { app.quit(); }
let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1400, height: 900, minWidth: 900, minHeight: 600,
    title: 'Vensera-База знаний', backgroundColor: '#0f172a', autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, devTools: false, spellcheck: true }
  });
  // Главное: Windows не отдаёт содержимое окна скриншотам, записи и демонстрации экрана — вместо него чёрный прямоугольник.
  win.setContentProtection(true);
  if (process.platform === 'darwin') {
    // На Mac без меню не работают Cmd+C / Cmd+V / Cmd+Q — оставляем только самое нужное
    Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'windowMenu' }]));
  } else Menu.setApplicationMenu(null);
  win.loadURL(START).catch(() => win.loadFile(path.join(__dirname, 'offline.html')));
  win.webContents.on('did-fail-load', (e, code, desc, url, isMain) => { if (isMain && code !== -3) win.loadFile(path.join(__dirname, 'offline.html')); });
  win.webContents.setWindowOpenHandler(({ url }) => { if (!isAllowed(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!isAllowed(url) && !url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); } });
  win.webContents.on('before-input-event', (e, input) => {
    const k = (input.key || '').toLowerCase(), mod = input.control || input.meta;
    if (k === 'f12' || (mod && input.shift && ['i', 'j', 'c'].includes(k)) || (mod && ['u', 's', 'p'].includes(k))) e.preventDefault();
    if (k === 'f5' || (mod && k === 'r')) { e.preventDefault(); win.webContents.reloadIgnoringCache(); }
  });
  win.on('page-title-updated', e => { e.preventDefault(); win.setTitle('Vensera-База знаний'); });
}

function setupUpdates() {
  if (!autoUpdater || !app.isPackaged) return;
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('update-downloaded', info => {
    dialog.showMessageBox(win, { type: 'info', buttons: ['Перезапустить сейчас', 'Позже'], defaultId: 0, cancelId: 1,
      title: 'Обновление Vensera-База знаний', message: 'Загружена новая версия VENSER ' + info.version + '.', detail: 'Перезапустить программу, чтобы установить её? Иначе обновление установится при следующем закрытии.' })
      .then(r => { if (r.response === 0) autoUpdater.quitAndInstall(); });
  });
  autoUpdater.on('error', () => {});
  const check = () => autoUpdater.checkForUpdates().catch(() => {});
  setTimeout(check, 5000);
  setInterval(check, 4 * 60 * 60 * 1000);
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'clipboard-sanitized-write', 'notifications', 'fullscreen'].includes(perm)));
  session.defaultSession.setUserAgent(session.defaultSession.getUserAgent() + ' VENSERApp/' + app.getVersion());
  createWindow();
  setupUpdates();
});
app.on('window-all-closed', () => app.quit());
app.on('activate', () => { if (!win) createWindow(); });
