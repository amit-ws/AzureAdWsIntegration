//package com.ws.wsAgenticSecurity;
//
//import java.io.*;
//import java.nio.charset.StandardCharsets;
//import java.time.Instant;
//
//public class Gateway {
//
//    public static void main(String[] args) throws Exception {
//
//        // Command to start GitHub MCP server
//        // Example (you adjust this to your actual GitHub MCP command)
//        ProcessBuilder pb = new ProcessBuilder(
//                "github-mcp-server"  // or "node", "python", etc.
//        );
//
//        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
//        Process githubProcess = pb.start();
//
//        BufferedReader claudeIn = new BufferedReader(
//                new InputStreamReader(System.in, StandardCharsets.UTF_8)
//        );
//        BufferedWriter claudeOut = new BufferedWriter(
//                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
//        );
//
//        BufferedReader githubIn = new BufferedReader(
//                new InputStreamReader(githubProcess.getInputStream(), StandardCharsets.UTF_8)
//        );
//        BufferedWriter githubOut = new BufferedWriter(
//                new OutputStreamWriter(githubProcess.getOutputStream(), StandardCharsets.UTF_8)
//        );
//
//        // Thread: Claude -> GitHub
//        new Thread(() -> forward("CLAUDE → GITHUB", claudeIn, githubOut)).start();
//
//        // Thread: GitHub -> Claude
//        new Thread(() -> forward("GITHUB → CLAUDE", githubIn, claudeOut)).start();
//
//        githubProcess.waitFor();
//    }
//
//    private static void forward(String direction,
//                                BufferedReader in,
//                                BufferedWriter out) {
//        try {
//            String line;
//            while ((line = in.readLine()) != null) {
//
//                log(direction, line);
//
//                out.write(line);
//                out.write("\n");
//                out.flush();
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    private static void log(String direction, String payload) {
//        System.err.println(
//                "[" + Instant.now() + "] " + direction + ": " + payload
//        );
//    }
//}
