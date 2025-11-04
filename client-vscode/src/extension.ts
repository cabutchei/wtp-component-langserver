import * as path from 'path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind, NodeModule} from 'vscode-languageclient/node';
import { Trace } from 'vscode-jsonrpc';




let client: LanguageClient;
let assemblyPanel: vscode.WebviewPanel | undefined;
let assemblyComponentUri: vscode.Uri | undefined;

export async function activate(ctx: vscode.ExtensionContext) {

    const editor = vscode.window.activeTextEditor;
    if (!editor) {
    return vscode.window.showWarningMessage("No active editor");
    }
    const docUri = editor.document.uri;   // <-- HERE

    const jar = vscode.workspace.getConfiguration('wtp').get<string>('serverJar');
    const serverJar = jar && jar.length > 0 ? jar: path.join(ctx.extensionPath, "server", "wtp-component-ls.jar");
    let a: NodeModule = { module: serverJar, transport: TransportKind.stdio };
    const serverOptions: ServerOptions = 
        { command: 'java',
            args: ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005,quiet=y', '-jar', serverJar, '-data', 'wtp-component-langserver/server-java/workspace'
], transport: TransportKind.stdio };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ language: 'xml' }],
        synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/{org.eclipse.wst.common.component,*.component}') }
    };

    client = new LanguageClient('wtpComponentLs', 'WTP Component Language Server', serverOptions, clientOptions);
    await client.start();
    client.setTrace(Trace.Verbose); // force verbose trace
    clientOptions.traceOutputChannel = vscode.window.createOutputChannel('WTP LS Trace');
    ctx.subscriptions.push(
        {
            dispose: () => { client.stop(); }
        }
    );

    // Commands
    ctx.subscriptions.push(
        vscode.commands.registerCommand('wtp.configureDeploymentAssembly', async (uri?: vscode.Uri) => {
            const doc = uri ?? vscode.window.activeTextEditor?.document.uri;
            if (!doc) {
                vscode.window.showWarningMessage('No component file selected.');
                return;
            }
            await openDeploymentAssembly(ctx, doc);
        })
    );

    ctx.subscriptions.push(
        vscode.commands.registerCommand('wtp.addToDeploymentAssembly', async (uri?: vscode.Uri) => {
            const target = uri ?? (await pickWorkspaceResource());
            if (!target) return;
            const comp = await findNearestComponentFile(target);
            if (!comp) return vscode.window.showWarningMessage('No .component file found in this project');
            const res = await client.sendRequest<any>('component/addMapping', {
                uri: comp.toString(),
                source: vscode.workspace.asRelativePath(target),
                deployPath: await askDeployPath()
            });
            if (res?.applied) vscode.window.showInformationMessage('Mapping added.');
        })
    );

    // // list mappings
    // const res = await client.sendRequest<{ mappings: { source: string; deployPath: string }[] }>(
    //     'component/listMappings', { uri: docUri.toString() }
    // );

    // // add mapping
    // const addRes = await client.sendRequest<{ applied: boolean; edit?: import('vscode').WorkspaceEdit }>(
    //     'component/addMapping',
    //     { uri: docUri.toString(), source: '/src', deployPath: '/WEB-INF/classes' }
    // );

    // If the server returns a WorkspaceEdit, you can also apply it from the client (optional):
    // await vscode.workspace.applyEdit(addRes.edit);    
    }

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

async function openDeploymentAssembly(ctx: vscode.ExtensionContext, componentUri: vscode.Uri) {
    assemblyComponentUri = componentUri;
    if (!assemblyPanel) {
        assemblyPanel = vscode.window.createWebviewPanel(
            'wtpDeploymentAssembly',
            'Deployment Assembly',
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [ctx.extensionUri]
            }
        );
        assemblyPanel.onDidDispose(() => {
            assemblyPanel = undefined;
            assemblyComponentUri = undefined;
        });
        assemblyPanel.webview.onDidReceiveMessage(async (message: any) => {
            if (!assemblyPanel) return;
            if (!message || typeof message !== 'object') return;
            switch (message.type) {
                case 'ready':
                case 'refresh':
                    if (assemblyComponentUri) {
                        await postAssemblyData(assemblyPanel, assemblyComponentUri);
                    }
                    break;
                case 'addMapping':
                    await handleAddMappingMessage(assemblyPanel, message);
                    break;
                default:
                    break;
            }
        });
        assemblyPanel.webview.html = getDeploymentAssemblyHtml(assemblyPanel.webview);
    } else {
        assemblyPanel.reveal(vscode.ViewColumn.Active);
    }

    if (assemblyPanel) {
        assemblyPanel.title = `Deployment Assembly — ${path.basename(componentUri.fsPath)}`;
        await postAssemblyData(assemblyPanel, componentUri);
    }
}

async function postAssemblyData(panel: vscode.WebviewPanel, componentUri: vscode.Uri) {
    try {
        const res = await client.sendRequest<any>('component/listMappings', { uri: componentUri.toString() });
        const mappings = res?.mappings ?? [];
        const projects = getWorkspaceProjects();
        const selected = vscode.workspace.getWorkspaceFolder(componentUri)?.uri.toString() ?? null;
        await panel.webview.postMessage({
            type: 'data',
            component: {
                uri: componentUri.toString(),
                name: path.basename(componentUri.fsPath)
            },
            mappings,
            projects,
            selectedProject: selected
        });
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        await panel.webview.postMessage({ type: 'error', message });
    }
}

async function handleAddMappingMessage(panel: vscode.WebviewPanel, message: any) {
    if (!assemblyComponentUri) {
        await notifyWebview(panel, 'error', 'No component selected.');
        return;
    }
    const componentUri = assemblyComponentUri;
    const projectUriValue = typeof message.projectUri === 'string' ? message.projectUri : '';
    if (!projectUriValue) {
        await notifyWebview(panel, 'error', 'Select a project to add.');
        return;
    }
    const workspaceFolders = vscode.workspace.workspaceFolders ?? [];
    const projectFolder = workspaceFolders.find(folder => folder.uri.toString() === projectUriValue);
    if (!projectFolder) {
        await notifyWebview(panel, 'error', 'Selected project is no longer available.');
        return;
    }

    const segments = parseSubpath(typeof message.subpath === 'string' ? message.subpath : '');
    if (segments === undefined) {
        await notifyWebview(panel, 'error', 'Project path cannot contain ".." segments.');
        return;
    }

    let target = projectFolder.uri;
    if (segments.length) {
        target = vscode.Uri.joinPath(projectFolder.uri, ...segments);
    }

    if (!isWithin(projectFolder.uri.fsPath, target.fsPath)) {
        await notifyWebview(panel, 'error', 'Project path must stay within the selected project.');
        return;
    }

    const sourcePath = vscode.workspace.asRelativePath(target, false) || projectFolder.name;
    const deployPath = normalizeDeployPath(typeof message.deployPath === 'string' ? message.deployPath : '/');

    await panel.webview.postMessage({ type: 'adding', inProgress: true });
    try {
        const res = await client.sendRequest<any>('component/addMapping', {
            uri: componentUri.toString(),
            source: sourcePath,
            deployPath
        });
        if (res?.applied) {
            await notifyWebview(panel, 'info', 'Mapping added.');
            await postAssemblyData(panel, componentUri);
        } else {
            await notifyWebview(panel, 'error', 'Language server rejected the mapping.');
        }
    } catch (error) {
        const messageText = error instanceof Error ? error.message : String(error);
        await notifyWebview(panel, 'error', messageText);
    } finally {
        await panel.webview.postMessage({ type: 'adding', inProgress: false });
    }
}

function getWorkspaceProjects() {
    const folders = vscode.workspace.workspaceFolders ?? [];
    return folders.map(folder => ({
        name: folder.name,
        uri: folder.uri.toString(),
        fsPath: folder.uri.fsPath
    }));
}

function getDeploymentAssemblyHtml(webview: vscode.Webview): string {
    const nonce = getNonce();
    const csp = [
        "default-src 'none'",
        `style-src ${webview.cspSource} 'unsafe-inline'`,
        `script-src 'nonce-${nonce}'`,
        `img-src ${webview.cspSource} https: data:`
    ].join('; ');
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="${csp}">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Deployment Assembly</title>
  <style>
    body {
      font-family: var(--vscode-font-family);
      color: var(--vscode-editor-foreground);
      background: var(--vscode-editor-background);
      margin: 0;
      padding: 16px;
      line-height: 1.5;
    }
    h1, h2 {
      margin-top: 0;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 12px;
    }
    th, td {
      border-bottom: 1px solid var(--vscode-editorGroup-border);
      padding: 8px;
      text-align: left;
    }
    th {
      text-transform: uppercase;
      font-size: 11px;
      letter-spacing: 0.08em;
      color: var(--vscode-descriptionForeground);
    }
    tbody tr:hover {
      background: var(--vscode-list-hoverBackground);
    }
    .muted {
      color: var(--vscode-descriptionForeground);
    }
    .stack {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      align-items: end;
    }
    label {
      display: flex;
      flex-direction: column;
      font-size: 12px;
      gap: 4px;
    }
    select, input[type="text"] {
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border);
      padding: 4px 6px;
      border-radius: 2px;
    }
    button {
      padding: 6px 12px;
      border: none;
      border-radius: 2px;
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      cursor: pointer;
      height: fit-content;
    }
    button:disabled {
      opacity: 0.6;
      cursor: progress;
    }
    #alerts {
      min-height: 20px;
      color: var(--vscode-descriptionForeground);
    }
    #alerts.error {
      color: var(--vscode-errorForeground);
    }
  </style>
</head>
<body>
  <main class="stack">
    <section>
      <h1 id="component-name">Deployment Assembly</h1>
      <p class="muted" id="component-path"></p>
    </section>
    <section>
      <h2>Current Mappings</h2>
      <table role="grid" aria-label="Deployment mappings">
        <thead>
          <tr>
            <th scope="col">Source</th>
            <th scope="col">Deploy Path</th>
          </tr>
        </thead>
        <tbody id="mapping-body"></tbody>
      </table>
      <p id="empty-message" class="muted">No mappings defined yet.</p>
    </section>
    <section>
      <h2>Add Mapping</h2>
      <form id="add-form">
        <label>
          Project
          <select id="project-select"></select>
        </label>
        <label>
          Project path
          <input id="project-path" type="text" placeholder="src/main/webapp">
        </label>
        <label>
          Deploy path
          <input id="deploy-path" type="text" value="/">
        </label>
        <button id="add-button" type="submit">Add</button>
      </form>
    </section>
    <section id="alerts" role="status" aria-live="polite"></section>
  </main>
  <script nonce="${nonce}">
    (function() {
      const vscode = acquireVsCodeApi();
      const componentName = document.getElementById('component-name');
      const componentPath = document.getElementById('component-path');
      const mappingBody = document.getElementById('mapping-body');
      const emptyMessage = document.getElementById('empty-message');
      const projectSelect = document.getElementById('project-select');
      const projectPath = document.getElementById('project-path');
      const deployPath = document.getElementById('deploy-path');
      const addButton = document.getElementById('add-button');
      const alerts = document.getElementById('alerts');

      function setAlert(text, severity) {
        alerts.textContent = text || '';
        alerts.classList.toggle('error', severity === 'error');
      }

      function renderMappings(mappings) {
        mappingBody.innerHTML = '';
        if (!mappings || mappings.length === 0) {
          emptyMessage.style.display = 'block';
          return;
        }
        emptyMessage.style.display = 'none';
        const rows = mappings.map((mapping) => {
          const tr = document.createElement('tr');
          const source = document.createElement('td');
          source.textContent = mapping.source ?? '';
          const deploy = document.createElement('td');
          deploy.textContent = mapping.deployPath ?? '';
          tr.appendChild(source);
          tr.appendChild(deploy);
          return tr;
        });
        rows.forEach(row => mappingBody.appendChild(row));
      }

      function renderProjects(projects, selected) {
        projectSelect.innerHTML = '';
        if (!projects || projects.length === 0) {
          const option = document.createElement('option');
          option.textContent = 'No workspace projects';
          option.value = '';
          projectSelect.appendChild(option);
          projectSelect.disabled = true;
          addButton.disabled = true;
          return;
        }
        projectSelect.disabled = false;
        addButton.disabled = false;
        projects.forEach(project => {
          const option = document.createElement('option');
          option.value = project.uri;
          option.textContent = project.name;
          if (selected && selected === project.uri) {
            option.selected = true;
          }
          projectSelect.appendChild(option);
        });
      }

      window.addEventListener('message', event => {
        const message = event.data;
        if (!message || typeof message !== 'object') {
          return;
        }
        switch (message.type) {
          case 'data':
            componentName.textContent = \`Deployment Assembly — \${message.component?.name ?? ''}\`;
            componentPath.textContent = message.component?.uri ?? '';
            renderMappings(message.mappings);
            renderProjects(message.projects, message.selectedProject);
            setAlert('', 'info');
            break;
          case 'notification':
            setAlert(message.message, message.severity);
            break;
          case 'error':
            setAlert(message.message, 'error');
            break;
          case 'adding':
            addButton.disabled = !!message.inProgress;
            break;
          default:
            break;
        }
      });

      document.getElementById('add-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const projectUri = projectSelect.value;
        if (!projectUri) {
          setAlert('Select a project before adding a mapping.', 'error');
          return;
        }
        vscode.postMessage({
          type: 'addMapping',
          projectUri,
          subpath: projectPath.value,
          deployPath: deployPath.value
        });
      });

      vscode.postMessage({ type: 'ready' });
    })();
  </script>
</body>
</html>`;
}

function parseSubpath(input: string | undefined): string[] | undefined {
    if (!input) return [];
    const segments = input
        .split(/[\\/]/)
        .map(segment => segment.trim())
        .filter(Boolean);
    if (segments.some(segment => segment === '..')) {
        return undefined;
    }
    return segments.filter(segment => segment !== '.');
}

function normalizeDeployPath(value: string | undefined): string {
    const trimmed = (value ?? '/').trim();
    if (!trimmed) return '/';
    return trimmed;
}

function isWithin(parent: string, child: string): boolean {
    const normalParent = path.resolve(parent);
    const normalChild = path.resolve(child);
    if (process.platform === 'win32') {
        const parentLower = normalParent.toLowerCase();
        const childLower = normalChild.toLowerCase();
        return childLower === parentLower || childLower.startsWith(parentLower.endsWith(path.sep) ? parentLower : parentLower + path.sep);
    }
    return normalChild === normalParent || normalChild.startsWith(normalParent.endsWith(path.sep) ? normalParent : normalParent + path.sep);
}

async function notifyWebview(panel: vscode.WebviewPanel, severity: 'info' | 'error', message: string) {
    await panel.webview.postMessage({ type: 'notification', severity, message });
}

function getNonce(): string {
    const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < 32; i++) {
        result += letters.charAt(Math.floor(Math.random() * letters.length));
    }
    return result;
}

async function pickComponentFile(): Promise<vscode.Uri | undefined> {
    const picks = await vscode.workspace.findFiles(
        '**/{org.eclipse.wst.common.component,*.component}',
        '**/node_modules/**',
        20
    );
    if (picks.length === 1) return picks[0];
    const choice = await vscode.window.showQuickPick(
        picks.map(u => ({ label: vscode.workspace.asRelativePath(u), u })),
        { placeHolder: 'Pick component file' }
    );
    return choice?.u;
}

async function pickWorkspaceResource(): Promise<vscode.Uri | undefined> {
    const picks = await vscode.window.showOpenDialog({ canSelectFiles: true, canSelectFolders: true, canSelectMany: false });
    return picks?.[0];
}

async function findNearestComponentFile(start: vscode.Uri): Promise<vscode.Uri | undefined> {
    let folder = vscode.workspace.getWorkspaceFolder(start)?.uri;
    if (!folder) return undefined;
    const files = await vscode.workspace.findFiles(
        new vscode.RelativePattern(folder, '**/{org.eclipse.wst.common.component,*.component}'),
        '**/node_modules/**',
        1
    );
    return files[0];
}

async function askDeployPath(): Promise<string> {
    const val = await vscode.window.showInputBox({ prompt: 'Deploy path (e.g. WEB-INF/classes, /, /lib)', value: '/' });
    return val || '/';
}
