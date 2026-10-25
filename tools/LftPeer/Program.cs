using System.Net;
using LanFileTransfer;

// Windows 版のプロトコル実装をそのまま使う、Android 版との相互接続テスト用ピア。
//
//   serve <port> <dir> [password]    … 1 ファイル受信したら終了
//   send  <host> <port> <file> [password]

if (args.Length == 0)
{
    Console.WriteLine("usage: serve <port> <dir> [password] | send <host> <port> <file> [password]");
    return 2;
}

string? OptionalPassword(int index) =>
    args.Length > index && args[index].Length > 0 ? args[index] : null;

var progress = new Progress<TransferProgressInfo>(_ => { });
using var cts = new CancellationTokenSource();

try
{
    if (args[0] == "serve")
    {
        int port = int.Parse(args[1]);
        string dir = Path.GetFullPath(args[2]);
        string? password = OptionalPassword(3);

        Directory.CreateDirectory(dir);

        Console.WriteLine($"LISTENING port={port} dir={dir} encrypted={password != null}");
        Console.Out.Flush();

        await FileTransferService.StartReceivingAsync(
            IPAddress.Any,
            port,
            dir,
            password,
            progress,
            path =>
            {
                Console.WriteLine($"RECEIVED {Path.GetFileName(path)} {new FileInfo(path).Length}");
                Console.Out.Flush();
                cts.Cancel();
            },
            cts.Token,
            error =>
            {
                Console.WriteLine($"ERROR {error}");
                Console.Out.Flush();
            });

        Console.WriteLine("SERVE-END");
        return 0;
    }

    if (args[0] == "send")
    {
        string host = args[1];
        int port = int.Parse(args[2]);
        string file = Path.GetFullPath(args[3]);
        string? password = OptionalPassword(4);

        await FileTransferService.SendFileAsync(host, port, file, password, progress, cts.Token);

        Console.WriteLine($"SENT {Path.GetFileName(file)} {new FileInfo(file).Length}");
        return 0;
    }

    Console.WriteLine($"unknown command: {args[0]}");
    return 2;
}
catch (Exception ex)
{
    Console.WriteLine($"FAILED {ex.GetType().Name}: {ex.Message}");
    return 1;
}
