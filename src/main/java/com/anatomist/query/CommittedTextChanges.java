package com.anatomist.query;

import com.anatomist.version.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** One Git patch for verified committed Java inputs. Other frozen inputs use blob comparison. */
final class CommittedTextChanges {
    private CommittedTextChanges() {}
    static Map<String,List<DiffTextChanges.Hunk>> compare(GitRepository git,String base,String target,
                                                        Set<String> changed,Map<String,String> oldFiles,Map<String,String> newFiles) {
        List<String> paths=changed.stream().filter(p->p.endsWith(".java") && p.matches("[a-zA-Z0-9_./$ -]+")
                && oldFiles.containsKey(p) && newFiles.containsKey(p)).toList();
        if(paths.size()<2) return Map.of();
        try {
            var oldHashes=hashes(git,base,paths);var newHashes=hashes(git,target,paths);
            Set<String> eligible=new HashSet<>(paths);eligible.removeIf(p->!Objects.equals(oldHashes.get(p),oldFiles.get(p)) || !Objects.equals(newHashes.get(p),newFiles.get(p)));
            if(eligible.isEmpty()) return Map.of();
            String prefix=git.projectRelative().toString().replace('\\','/');if(!prefix.isEmpty()) prefix+="/";
            String relative=prefix;
            GitRepository.countInvocation();
            Process process=new ProcessBuilder("git","-C",git.root().toString(),"-c","core.quotepath=false","diff","--no-renames","--no-ext-diff","--no-textconv","--no-color","--text","--unified=0","--diff-algorithm=myers",base,target,"--",prefix+"*.java").start();
            Map<String,List<DiffTextChanges.Hunk>> result;
            process.getOutputStream().close();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var errors=executor.submit(()->process.getErrorStream().readAllBytes());
                var reader=executor.submit(()->{
                    Map<String,List<DiffTextChanges.Hunk>> output=new HashMap<>();String current=null;
                    try(var lines=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;
                        while((line=lines.readLine())!=null) {
                            if(line.startsWith("diff --git ")) current=null;
                            if(line.startsWith("+++ b/")) {
                                String path=line.substring(6).replaceFirst("\\t$","");
                                if(path.startsWith(relative)) path=path.substring(relative.length());
                                if(eligible.contains(path)) { current=path;output.put(current,new ArrayList<>()); }
                            }
                            if(current!=null) { var hunk=DiffTextChanges.hunk(line);if(hunk!=null) output.get(current).add(hunk); }
                        }
                    }return output;
                });
                try {
                    if(!process.waitFor(60,TimeUnit.SECONDS)) throw new IOException("Git patch timed out");
                    errors.get();if(process.exitValue()!=0) throw new IOException("Git patch failed");result=reader.get();
                } finally { if(process.isAlive()) process.destroyForcibly(); }
            }
            // Empty/malformed ranges fall back to the existing independently verified path.
            result.entrySet().removeIf(e->e.getValue().isEmpty());return result;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new SnapshotException("DIFF_TEXT_INTERRUPTED","Git comparison interrupted",interrupted);
        } catch(Exception unavailable) { return Map.of(); }
    }
    private static Map<String,String> hashes(GitRepository git,String sha,List<String> paths) throws Exception {
        GitRepository.countInvocation();
        Process process=new ProcessBuilder("git","-C",git.root().toString(),"cat-file","--batch").start();
        String prefix=git.projectRelative().toString().replace('\\','/');if(!prefix.isEmpty()) prefix+="/";
        String relative=prefix;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=executor.submit(()->{
                try(var out=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))) {
                    for(String path:paths) { out.write(sha+":"+relative+path);out.newLine(); }
                } return null;
            });
            var errors=executor.submit(()->process.getErrorStream().readAllBytes());
            var reader=executor.submit(()->{
                Map<String,String> result=new HashMap<>();var in=new BufferedInputStream(process.getInputStream());byte[] buffer=new byte[8192];
                for(String path:paths) {
                    var header=new ByteArrayOutputStream();int next;
                    while((next=in.read())!=-1 && next!='\n') header.write(next);
                    String[] fields=header.toString(StandardCharsets.UTF_8).split(" ");
                    if(fields.length!=3 || !fields[1].equals("blob")) continue;
                    long remaining=Long.parseLong(fields[2]);var hash=MessageDigest.getInstance("SHA-256");
                    while(remaining>0) { int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0) throw new EOFException();hash.update(buffer,0,n);remaining-=n; }
                    if(in.read()!='\n') throw new IOException("Invalid Git blob framing");result.put(path,HexFormat.of().formatHex(hash.digest()));
                }return result;
            });
            try {
                if(!process.waitFor(60,TimeUnit.SECONDS)) throw new IOException("Git blob verification timed out");
                writer.get();errors.get();return reader.get();
            } finally { if(process.isAlive()) process.destroyForcibly(); }
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
}
