const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('VENSER_APP', { protected: true });
