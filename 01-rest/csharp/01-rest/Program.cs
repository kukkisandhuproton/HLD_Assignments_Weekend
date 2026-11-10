using QuickBite.Rest;

/*
 * PROGRAM ENTRY POINT
 * ===================
 * This is where the application starts.
 *
 * This project can run in two modes:
 *
 *   server → Starts the REST API and waits for HTTP requests.
 *   check  → Runs the automatic checker against an already-running server.
 *
 * Run these modes in separate terminals:
 *
 *   dotnet run --no-launch-profile -- server
 *   dotnet run --no-launch-profile -- check
 *
 * Both modes belong to the same project, but each command starts a
 * separate process. The checker communicates with the server over HTTP.
 *
 * C# creates the Program class automatically for this top-level code.
 */

// STEP 1: Read the startup mode.
// If no mode is supplied, start the server.
string mode = args.Length > 0
    ? args[0].ToLowerInvariant()
    : "server";

switch (mode)
{
    case "server":
        // STEP 2-SERVER: Start the API.
        // Pass any remaining arguments to ASP.NET Core.
        await OrderApi.RunAsync(args.Skip(1).ToArray());
        break;

    case "check":
        // STEP 2-CHECKER: Run tests against the running API.
        await Check.RunAsync();
        break;

    default:
        Console.WriteLine("Unknown mode. Use: server or check");
        Environment.ExitCode = 1;
        break;
}