const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('VENSER_APP', {
  protected: true,
  // Чтение буфера обмена для пункта «Вставить» в меню правой кнопки
  readClipboard: () => ipcRenderer.invoke('venser-clipboard-read')
});
