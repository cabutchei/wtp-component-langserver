import * as path from 'path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind, type DocumentSelector } from 'vscode-languageclient/node';
import { Trace } from 'vscode-jsonrpc';

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

    ctx.subscriptions.push(
        {
            dispose: () => { client.stop(); }
        }
    );

    ctx.subscriptions.push(
        vscode.commands.registerCommand('wtp.configureDeploymentAssembly', async (uri: vscode.Uri) => {
            const doc = uri ?? vscode.window.activeTextEditor?.document.uri;
            if (!doc) return;
            const res = await client.sendRequest<any>('component/listMappings', { uri: doc.toString() });
            await showMappingQuickPick(res?.mappings || [], doc.toString());
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
}

export function deactivate(): Thenable<void> | undefined {
    return client?.stop();
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

async function showMappingQuickPick(mappings: any[], compUri: string) {
    const items = mappings.map(m => ({ label: m.source, description: '→ ' + m.deployPath }));
    const _ = await vscode.window.showQuickPick(items, { canPickMany: false, placeHolder: 'Existing mappings' });
}
