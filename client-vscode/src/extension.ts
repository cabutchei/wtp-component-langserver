import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    type DocumentSelector
} from 'vscode-languageclient/node';
import { Trace } from 'vscode-jsonrpc';
import type { WorkspaceEdit as LspWorkspaceEdit } from 'vscode-languageserver-protocol';

let client: LanguageClient;

export async function activate(ctx: vscode.ExtensionContext) {
    const jar = vscode.workspace.getConfiguration('wtp').get<string>('serverJar');
    const serverJar = jar && jar.length > 0 ? jar : path.join(ctx.extensionPath, 'server', 'wtp-component-ls.jar');

    const serverOptions: ServerOptions = {
        run: { command: 'java', args: ['-jar', serverJar], transport: TransportKind.stdio },
        debug: { command: 'java', args: ['-jar', serverJar], transport: TransportKind.stdio }
    };

    const documentSelector: DocumentSelector = [
        { scheme: 'file', language: 'component' },
        { scheme: 'file', language: 'xml', pattern: '**/{org.eclipse.wst.common.component,*.component}' }
    ];

    const traceOutputChannel = vscode.window.createOutputChannel('WTP LS Trace');

    const clientOptions: LanguageClientOptions = {
        documentSelector,
        synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/{org.eclipse.wst.common.component,*.component}') },
        traceOutputChannel
    };

    client = new LanguageClient('wtpComponentLs', 'WTP Component Language Server', serverOptions, clientOptions);
    await client.start();
    client.setTrace(Trace.Verbose);

    ctx.subscriptions.push({ dispose: () => { client.stop(); } });

    ctx.subscriptions.push(
        vscode.commands.registerCommand('wtp.configureDeploymentAssembly', async (resource?: vscode.Uri) => {
            const context = await resolveDeploymentAssemblyContext(resource);
            if (!context) return;
            DeploymentAssemblyPanel.createOrShow(ctx, context.workspaceFolder, context.componentUri);
        })
    );

    ctx.subscriptions.push(
        vscode.commands.registerCommand('wtp.addToDeploymentAssembly', async (uri?: vscode.Uri) => {
            const target = uri ?? (await pickWorkspaceResource());
            if (!target) return;
            const comp = await findNearestComponentFile(target);
            if (!comp) {
                return vscode.window.showWarningMessage('No .component file found in this project');
            }
            const xml = await readFileAsString(comp);
            if (!xml) return;

            const sourcePath = ensureLeadingSlash(vscode.workspace.asRelativePath(target, false));
            const deployPath = await askDeployPath();

            try {
                const res = await client.sendRequest<AddMappingResult>('component/addMapping', {
                    uri: comp.toString(),
                    text: xml,
                    source: sourcePath,
                    deployPath
                });
                if (res?.edit) await applyLspWorkspaceEdit(res.edit);
                if (res?.applied) vscode.window.showInformationMessage('Mapping added.');
                else vscode.window.showInformationMessage('Mapping already present.');
            } catch (err) {
                vscode.window.showErrorMessage(`Failed to add mapping: ${err instanceof Error ? err.message : String(err)}`);
            }
        })
    );
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
}

interface MappingDto {
    source: string;
    deployPath: string;
}

interface ListMappingsResult {
    mappings: MappingDto[];
}

interface AddMappingResult {
    applied: boolean;
    edit?: LspWorkspaceEdit;
}

async function resolveDeploymentAssemblyContext(resource?: vscode.Uri) {
    let folder: vscode.WorkspaceFolder | undefined;
    if (resource) {
        folder = vscode.workspace.getWorkspaceFolder(resource);
        if (!folder && resource.scheme === 'vscode-userdata') {
            folder = vscode.workspace.workspaceFolders?.[0];
        }
    }
    if (!folder) {
        if (!vscode.workspace.workspaceFolders || vscode.workspace.workspaceFolders.length === 0) {
            vscode.window.showWarningMessage('Open a workspace to configure deployment assembly.');
            return;
        }
        folder = await vscode.window.showWorkspaceFolderPick();
    }
    if (!folder) return;
    const componentUri = await findComponentFileInWorkspaceFolder(folder);
    if (!componentUri) {
        vscode.window.showWarningMessage(`No component file found under ${folder.name}.`);
        return;
    }
    return { workspaceFolder: folder, componentUri };
}

async function findComponentFileInWorkspaceFolder(folder: vscode.WorkspaceFolder): Promise<vscode.Uri | undefined> {
    const files = await vscode.workspace.findFiles(
        new vscode.RelativePattern(folder, '**/{org.eclipse.wst.common.component,*.component}'),
        '**/node_modules/**',
        1
    );
    return files[0];
}

async function findNearestComponentFile(start: vscode.Uri): Promise<vscode.Uri | undefined> {
    const folder = vscode.workspace.getWorkspaceFolder(start)?.uri;
    if (!folder) return undefined;
    const files = await vscode.workspace.findFiles(
        new vscode.RelativePattern(folder, '**/{org.eclipse.wst.common.component,*.component}'),
        '**/node_modules/**',
        1
    );
    return files[0];
}

async function pickWorkspaceResource(): Promise<vscode.Uri | undefined> {
    const picks = await vscode.window.showOpenDialog({ canSelectFiles: true, canSelectFolders: true, canSelectMany: false });
    return picks?.[0];
}

async function askDeployPath(): Promise<string> {
    const val = await vscode.window.showInputBox({ prompt: 'Deploy path (e.g. /, /WEB-INF/classes, /lib)', value: '/' });
    const chosen = (val || '/').trim();
    return chosen.startsWith('/') ? chosen : `/${chosen}`;
}

async function readFileAsString(uri: vscode.Uri): Promise<string | undefined> {
    try {
        const bytes = await vscode.workspace.fs.readFile(uri);
        return new TextDecoder('utf-8').decode(bytes);
    } catch (err) {
        vscode.window.showErrorMessage(`Unable to read ${vscode.workspace.asRelativePath(uri)}: ${err}`);
        return undefined;
    }
}

async function applyLspWorkspaceEdit(edit?: LspWorkspaceEdit) {
    if (!edit) return false;
    const vscodeEdit = await client.protocol2CodeConverter.asWorkspaceEdit(edit);
    if (!vscodeEdit) return false;
    return vscode.workspace.applyEdit(vscodeEdit);
}

class DeploymentAssemblyPanel {
    private static panels = new Map<string, DeploymentAssemblyPanel>();

    private readonly panel: vscode.WebviewPanel;
    private readonly workspaceFolder: vscode.WorkspaceFolder;
    private readonly componentUri: vscode.Uri;
    private readonly extensionContext: vscode.ExtensionContext;
    private mappings: MappingDto[] = [];

    static createOrShow(
        ctx: vscode.ExtensionContext,
        workspaceFolder: vscode.WorkspaceFolder,
        componentUri: vscode.Uri
    ) {
        const key = workspaceFolder.uri.toString();
        const existing = DeploymentAssemblyPanel.panels.get(key);
        if (existing) {
            existing.panel.reveal(existing.panel.viewColumn);
            existing.refreshMappings();
            return;
        }

        const panel = vscode.window.createWebviewPanel(
            'wtpConfigureDeploymentAssembly',
            `Configure Deployment Assembly (${workspaceFolder.name})`,
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true
            }
        );

        const instance = new DeploymentAssemblyPanel(panel, ctx, workspaceFolder, componentUri);
        DeploymentAssemblyPanel.panels.set(key, instance);
    }

    private constructor(
        panel: vscode.WebviewPanel,
        extensionContext: vscode.ExtensionContext,
        workspaceFolder: vscode.WorkspaceFolder,
        componentUri: vscode.Uri
    ) {
        this.panel = panel;
        this.extensionContext = extensionContext;
        this.workspaceFolder = workspaceFolder;
        this.componentUri = componentUri;

        panel.onDidDispose(() => {
            DeploymentAssemblyPanel.panels.delete(this.workspaceFolder.uri.toString());
        }, null, this.extensionContext.subscriptions);

        panel.webview.onDidReceiveMessage(msg => this.handleMessage(msg), undefined, this.extensionContext.subscriptions);
        this.setHtml();
    }

    private setHtml() {
        const webview = this.panel.webview;
        const nonce = getNonce();
        const contextInfo = {
            workspace: this.workspaceFolder.name,
            componentPath: vscode.workspace.asRelativePath(this.componentUri, false)
        };

        webview.html = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${webview.cspSource} https:; script-src 'nonce-${nonce}'; style-src ${webview.cspSource} 'unsafe-inline';">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Configure Deployment Assembly</title>
    <style>
        body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); background: var(--vscode-editor-background); margin: 0; padding: 16px; }
        h1 { font-size: 20px; margin-bottom: 8px; }
        section { margin-bottom: 24px; }
        table { width: 100%; border-collapse: collapse; }
        th, td { border-bottom: 1px solid var(--vscode-editorWidget-border); padding: 6px 8px; text-align: left; }
        tbody tr:hover { background: var(--vscode-list-hoverBackground); }
        .form-row { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
        label { display: flex; flex-direction: column; font-size: 12px; gap: 4px; min-width: 200px; }
        input[type="text"] { padding: 6px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); border-radius: 4px; }
        button { padding: 6px 12px; background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; border-radius: 4px; cursor: pointer; }
        button:hover { background: var(--vscode-button-hoverBackground); }
        .status { margin-top: 8px; min-height: 20px; font-size: 12px; color: var(--vscode-descriptionForeground); }
        .actions { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; }
        .empty { font-style: italic; color: var(--vscode-descriptionForeground); }
    </style>
</head>
<body>
    <section>
        <h1>Configure Deployment Assembly</h1>
        <div>Workspace: <strong>${contextInfo.workspace}</strong></div>
        <div>Component file: <code>${contextInfo.componentPath}</code></div>
    </section>

    <section>
        <h2>Current Mappings</h2>
        <table>
            <thead>
                <tr>
                    <th>Source Path</th>
                    <th>Deploy Path</th>
                </tr>
            </thead>
            <tbody id="mappingRows">
                <tr class="empty"><td colspan="2">Loading…</td></tr>
            </tbody>
        </table>
    </section>

    <section>
        <h2>Add Mapping</h2>
        <div class="form-row">
            <label>
                Source Path
                <input type="text" id="sourcePath" placeholder="/src/main/webapp">
            </label>
            <label>
                Deploy Path
                <input type="text" id="deployPath" placeholder="/">
            </label>
        </div>
        <div class="actions">
            <button id="pickResource">Select Workspace Folder…</button>
            <button id="addMapping">Add Mapping</button>
        </div>
        <div class="status" id="status"></div>
    </section>

    <script nonce="${nonce}">
        const vscodeApi = acquireVsCodeApi();

        const mappingRows = document.getElementById('mappingRows');
        const sourceInput = document.getElementById('sourcePath');
        const deployInput = document.getElementById('deployPath');
        const statusEl = document.getElementById('status');
        document.getElementById('pickResource').addEventListener('click', () => {
            vscodeApi.postMessage({ type: 'selectResource' });
        });
        document.getElementById('addMapping').addEventListener('click', () => {
            const source = sourceInput.value.trim();
            const deploy = deployInput.value.trim() || '/';
            if (!source) {
                setStatus('Provide a source path inside the workspace.');
                return;
            }
            vscodeApi.postMessage({ type: 'addMapping', payload: { source, deploy } });
        });

        window.addEventListener('message', event => {
            const { type, payload } = event.data;
            if (type === 'mappings') {
                renderMappings(payload);
            } else if (type === 'resourceSelected') {
                if (payload?.source) {
                    sourceInput.value = payload.source;
                    setStatus('');
                }
            } else if (type === 'status') {
                setStatus(payload?.message || '');
            } else if (type === 'error') {
                setStatus(payload?.message || 'An unexpected error occurred.');
            }
        });

        function renderMappings(mappings) {
            mappingRows.innerHTML = '';
            if (!mappings || mappings.length === 0) {
                mappingRows.innerHTML = '<tr class="empty"><td colspan="2">No mappings defined.</td></tr>';
                return;
            }
            for (const m of mappings) {
                const tr = document.createElement('tr');
                const tdSource = document.createElement('td');
                tdSource.textContent = m.source || '';
                const tdDeploy = document.createElement('td');
                tdDeploy.textContent = m.deployPath || '';
                tr.appendChild(tdSource);
                tr.appendChild(tdDeploy);
                mappingRows.appendChild(tr);
            }
        }

        function setStatus(message) {
            statusEl.textContent = message;
        }

        vscodeApi.postMessage({ type: 'ready' });
    </script>
</body>
</html>`;
    }

    private async handleMessage(message: any) {
        switch (message?.type) {
            case 'ready':
                await this.refreshMappings();
                break;
            case 'addMapping':
                await this.applyMapping(message.payload?.source, message.payload?.deploy);
                break;
            case 'selectResource':
                await this.pickResourceForWebview();
                break;
            default:
                break;
        }
    }

    private async refreshMappings() {
        try {
            const xml = await readFileAsString(this.componentUri);
            if (!xml) return;
            const result = await client.sendRequest<ListMappingsResult>('component/listMappings', {
                uri: this.componentUri.toString(),
                text: xml
            });
            this.mappings = result?.mappings ?? [];
            this.panel.webview.postMessage({ type: 'mappings', payload: this.mappings });
            this.panel.webview.postMessage({ type: 'status', payload: { message: '' } });
        } catch (err) {
            this.panel.webview.postMessage({
                type: 'error',
                payload: { message: `Unable to load mappings: ${err instanceof Error ? err.message : String(err)}` }
            });
        }
    }

    private async applyMapping(sourcePath: string, deployPath: string) {
        if (!sourcePath) {
            this.panel.webview.postMessage({ type: 'status', payload: { message: 'Source path is required.' } });
            return;
        }
        try {
            const xml = await readFileAsString(this.componentUri);
            if (!xml) return;
            const res = await client.sendRequest<AddMappingResult>('component/addMapping', {
                uri: this.componentUri.toString(),
                text: xml,
                source: sourcePath,
                deployPath
            });
            if (res?.edit) await applyLspWorkspaceEdit(res.edit);
            if (res?.applied) {
                this.panel.webview.postMessage({ type: 'status', payload: { message: 'Mapping added.' } });
            } else {
                this.panel.webview.postMessage({ type: 'status', payload: { message: 'Mapping already exists.' } });
            }
            await this.refreshMappings();
        } catch (err) {
            this.panel.webview.postMessage({
                type: 'error',
                payload: { message: `Failed to add mapping: ${err instanceof Error ? err.message : String(err)}` }
            });
        }
    }

    private async pickResourceForWebview() {
        const selection = await vscode.window.showOpenDialog({
            canSelectFiles: true,
            canSelectFolders: true,
            canSelectMany: false,
            defaultUri: this.workspaceFolder.uri
        });
        const selected = selection?.[0];
        if (!selected) return;
        const relative = vscode.workspace.asRelativePath(selected, false);
        this.panel.webview.postMessage({ type: 'resourceSelected', payload: { source: ensureLeadingSlash(relative) } });
    }
}

function getNonce() {
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    return Array.from({ length: 32 }, () => possible.charAt(Math.floor(Math.random() * possible.length))).join('');
}

function ensureLeadingSlash(value: string) {
    if (!value.startsWith('/')) {
        return '/' + value;
    }
    return value;
}
