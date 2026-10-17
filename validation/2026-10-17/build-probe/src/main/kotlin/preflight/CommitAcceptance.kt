package preflight

import com.sun.jna.*
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val codec = Json { prettyPrint = true }
internal fun sha(path: Path): String = Files.newInputStream(path).use { input ->
    val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
    while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)}
    md.digest().joinToString(""){"%02x".format(it)}
}
private fun evidence(path: Path): String? = if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) sha(path) else null
private fun durable(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val tmp=path.resolveSibling(path.fileName.toString()+".writing")
    FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE).use { channel ->
        val bytes=java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true)
    }
    Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
}
internal interface NativeCommit : StdCallLibrary {
    fun ReplaceFileW(source: WString,candidate: WString,backup: WString,flags:Int,exclude:Pointer?,reserved:Pointer?):Boolean
    fun MoveFileExW(source:WString,target:WString,flags:Int):Boolean
}
private val nativeCommit by lazy { Native.load("kernel32",NativeCommit::class.java) }
private fun win(path:Path)=WString("\\\\?\\"+path.toAbsolutePath().normalize())
@Serializable
private data class Record(val source:String,val candidate:String,val backup:String,val sourceHash:String,
    val candidateHash:String,val phase:String="Prepared",val error:Int=0)
private enum class Outcome { UNCHANGED, COMMITTED, NEEDS_ATTENTION }

/** Technical reference coordinator. Fixture-root guard prevents touching user media. */
private class CommitProbe(val root:Path, metadataRoot:Path=root, private val checkpoint:(String)->Unit={}) : AutoCloseable {
    private val archive=metadataRoot.also{Files.createDirectories(it)}
    private val db=DriverManager.getConnection("jdbc:sqlite:${archive.resolve("commit.sqlite")}")
    var nativeCalls=0;private set
    init {
        db.createStatement().use { it.execute("PRAGMA journal_mode=DELETE");it.execute("PRAGMA synchronous=FULL")
            it.execute("CREATE TABLE IF NOT EXISTS commit_record(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)") }
    }
    fun readOnlyStore() { db.createStatement().use{it.execute("PRAGMA query_only=ON")} }
    private fun paths(r:Record)=listOf(Path.of(r.source),Path.of(r.candidate),Path.of(r.backup)).onEach {
        require(it.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {"Outside test fixture root"}
    }
    private fun save(r:Record) {
        val text=codec.encodeToString(r)
        db.prepareStatement("INSERT OR REPLACE INTO commit_record VALUES(1,?)").use {it.setString(1,text);it.executeUpdate()}
        checkpoint("After${r.phase}Db")
        durable(archive.resolve("commit.json"),text)
        checkpoint("After${r.phase}Mirror")
    }
    private fun load():Record = db.createStatement().use { stmt ->stmt.executeQuery("SELECT payload FROM commit_record WHERE id=1").use {
        check(it.next());codec.decodeFromString<Record>(it.getString(1))
    } }
    fun prepare(source:Path,candidate:Path,backup:Path=root.resolve("original.backup"),stopped:Boolean=false,
        validated:Boolean=true,allowGrowth:Boolean=false) {
        require(!stopped && validated)
        val r=Record(source.toString(),candidate.toString(),backup.toString(),"","");paths(r)
        require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSameFile(source,candidate)&&!Files.exists(backup,LinkOption.NOFOLLOW_LINKS))
        require(Files.size(candidate)>0&&(allowGrowth||Files.size(candidate)<Files.size(source)))
        require(Files.getFileStore(source)==Files.getFileStore(candidate)&&Files.getFileStore(source)==Files.getFileStore(backup.parent))
        FileChannel.open(candidate,StandardOpenOption.WRITE).use{it.force(true)}
        save(r.copy(sourceHash=sha(source),candidateHash=sha(candidate)))
    }
    fun reconcile(persist:Boolean=true):Outcome {
        val r=load();val (source,candidate,backup)=paths(r)
        val s=evidence(source);val c=evidence(candidate);val b=evidence(backup)
        return when {
            s==r.candidateHash && b==r.sourceHash -> {
                if(persist&&r.phase!="Committed")save(r.copy(phase="Committed"));Outcome.COMMITTED
            }
            s==r.sourceHash && c==r.candidateHash && b==null -> Outcome.UNCHANGED
            else -> Outcome.NEEDS_ATTENTION
        }
    }
    fun publish(stopped:Boolean=false, substitute:((Record)->Pair<Boolean,Int>)?=null):Outcome {
        require(!stopped)
        if(reconcile()==Outcome.COMMITTED)return Outcome.COMMITTED
        val r=load();val (source,candidate,backup)=paths(r)
        require(evidence(source)==r.sourceHash && evidence(candidate)==r.candidateHash && !Files.exists(backup))
        save(r.copy(phase="PublishIntent"));checkpoint("BeforeNative")
        require(evidence(source)==r.sourceHash&&evidence(candidate)==r.candidateHash&&!Files.exists(backup))
        nativeCalls++
        val result=substitute?.invoke(r)?:run {
            val ok=nativeCommit.ReplaceFileW(win(source),win(candidate),win(backup),0,null,null)
            ok to if(ok)0 else Native.getLastError()
        }
        checkpoint("AfterNative")
        val actual=reconcile(false)
        save(r.copy(phase=if(actual==Outcome.COMMITTED)"Published" else "NeedsReconcile",error=result.second))
        if(actual==Outcome.COMMITTED)save(r.copy(phase="Committed",error=result.second))
        return actual
    }
    override fun close()=db.close()
}

private fun fixture(root:Path):Pair<Path,Path> {
    Files.createDirectories(root);val source=root.resolve("中文 原片.mp4");val candidate=root.resolve("candidate.partial.mp4")
    Files.write(source,ByteArray(65536){(it%127).toByte()});Files.write(candidate,ByteArray(4096){(it%53).toByte()})
    return source to candidate
}
internal fun commitCrashChild(root:Path, point:String) {
    val source=root.resolve("中文 原片.mp4");val candidate=root.resolve("candidate.partial.mp4")
    CommitProbe(root){if(it==point)Runtime.getRuntime().halt(83)}.use { it.prepare(source,candidate);it.publish() }
    error("Crash checkpoint not reached: $point")
}
private fun childCommand():List<String> {
    val command=ProcessHandle.current().info().command().orElse("")
    return if(command.endsWith("ErgouPreflight.exe",true))listOf(command) else
        listOf(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
            "-Dergou.app.home=${System.getProperty("ergou.app.home")}","-cp",System.getProperty("java.class.path"),"preflight.MainKt")
}

internal fun commitAcceptance(base:Path,report:Path) {
    val root=base.resolve("run-${System.currentTimeMillis()}")
    Files.createDirectories(root);val results=mutableListOf<Map<String,String>>()
    fun case(name:String, test:(Path)->String) {
        val caseRoot=root.resolve(name);Files.createDirectories(caseRoot)
        try {results.add(mapOf("test" to name,"status" to "PASS","detail" to test(caseRoot)))}
        catch(ex:Throwable){results.add(mapOf("test" to name,"status" to "FAIL","error" to ex.stackTraceToString()))}
        saveStrings(report,results)
    }
    fun rejects(name:String, test:(CommitProbe,Path,Path)->Unit) = case(name){ dir ->
        val(s,c)=fixture(dir);val before=sha(s)
        CommitProbe(dir).use { p -> check(runCatching{test(p,s,c)}.isFailure);check(p.nativeCalls==0) }
        check(sha(s)==before&&Files.exists(c));"precondition rejected before native mutation"
    }
    case("same-name-publish-backup-and-file-id"){dir ->
        val(s,c)=fixture(dir);val old=sha(s);val expected=sha(c)
        val oldId=Files.readAttributes(s,BasicFileAttributes::class.java).fileKey()
        val newId=Files.readAttributes(c,BasicFileAttributes::class.java).fileKey()
        CommitProbe(dir).use {p ->p.prepare(s,c);check(p.publish()==Outcome.COMMITTED);check(p.publish()==Outcome.COMMITTED);check(p.nativeCalls==1)}
        check(sha(s)==expected&&sha(dir.resolve("original.backup"))==old)
        check(Files.readAttributes(s,BasicFileAttributes::class.java).fileKey()==newId)
        check(Files.readAttributes(dir.resolve("original.backup"),BasicFileAttributes::class.java).fileKey()==oldId)
        "new target is candidate identity, backup is original identity, retry idempotent"
    }
    rejects("stop-before-prepare"){p,s,c->p.prepare(s,c,stopped=true)}
    rejects("validation-failed"){p,s,c->p.prepare(s,c,validated=false)}
    rejects("empty-candidate"){p,s,c->Files.write(c,byteArrayOf());p.prepare(s,c)}
    rejects("larger-candidate"){p,s,c->Files.write(c,ByteArray(70000));p.prepare(s,c)}
    rejects("same-file-rejected"){p,s,_->p.prepare(s,s)}
    rejects("hardlink-candidate-rejected"){p,s,c->Files.delete(c);Files.createLink(c,s);p.prepare(s,c)}
    rejects("backup-conflict"){p,s,c->Files.writeString(s.parent.resolve("original.backup"),"do not overwrite");p.prepare(s,c)}
    rejects("read-only-database-blocks-publication"){p,s,c->p.readOnlyStore();p.prepare(s,c)}
    rejects("stop-after-prepared"){p,s,c->p.prepare(s,c);p.publish(stopped=true)}
    case("source-changed-after-prepare"){dir ->val(s,c)=fixture(dir);CommitProbe(dir).use {p->
        p.prepare(s,c);Files.writeString(s,"external new source");check(runCatching{p.publish()}.isFailure)
        check(p.reconcile()==Outcome.NEEDS_ATTENTION&&p.nativeCalls==0&&Files.readString(s)=="external new source")};"external content preserved"}
    case("candidate-changed-after-prepare"){dir->val(s,c)=fixture(dir);val old=sha(s);CommitProbe(dir).use{p->
        p.prepare(s,c);Files.writeString(c,"tampered candidate");check(runCatching{p.publish()}.isFailure);check(p.nativeCalls==0)}
        check(sha(s)==old);"source unchanged"}
    case("locked-source-then-retry"){dir->val(s,c)=fixture(dir);val old=sha(s)
        CommitProbe(dir).use{p->p.prepare(s,c)
            val h=Kernel32.INSTANCE.CreateFile(s.toString(),WinNT.GENERIC_READ,0,null,WinNT.OPEN_EXISTING,0,null)
            check(h!=WinBase.INVALID_HANDLE_VALUE)
            try{check(runCatching{p.publish()}.isFailure);check(p.nativeCalls==0);check(sha(c).isNotEmpty())}finally{Kernel32.INSTANCE.CloseHandle(h)}
            check(sha(s)==old);check(p.publish()==Outcome.COMMITTED)
        };"real Win32 exclusive handle blocks precheck; unlocked retry commits"}
    case("readonly-source-preserved"){dir->val(s,c)=fixture(dir);val old=sha(s)
        CommitProbe(dir).use{p->p.prepare(s,c);Files.setAttribute(s,"dos:readonly",true)
            try{check(p.publish()==Outcome.UNCHANGED)}finally{Files.setAttribute(s,"dos:readonly",false)}
            check(sha(s)==old)
        };"real readonly attribute blocks publish without deletion"}
    case("long-unicode-path"){dir->
        var nested=dir;repeat(9){nested=nested.resolve("长路径-0123456789-$it-abcdefghijklmnop")}
        val(s,c)=fixture(nested);check(s.toString().length>300)
        CommitProbe(nested,metadataRoot=dir.resolve("archive")).use{p->p.prepare(s,c);check(p.publish()==Outcome.COMMITTED)}
        "pathLength=${s.toString().length}; long media path with separate short central catalog"}
    case("source-changed-at-final-boundary"){dir->val(s,c)=fixture(dir)
        CommitProbe(dir){if(it=="BeforeNative")Files.writeString(s,"external boundary change")}.use{p->
            p.prepare(s,c);check(runCatching{p.publish()}.isFailure);check(p.nativeCalls==0&&p.reconcile()==Outcome.NEEDS_ATTENTION)}
        check(Files.readString(s)=="external boundary change"&&Files.exists(c));"final identity check blocks native mutation"}
    case("mirror-write-failure-blocks-native"){dir->val(s,c)=fixture(dir);val old=sha(s)
        CommitProbe(dir).use{p->p.prepare(s,c);Files.delete(dir.resolve("commit.json"));Files.createDirectory(dir.resolve("commit.json"))
            check(runCatching{p.publish()}.isFailure);check(p.nativeCalls==0&&sha(s)==old)
            Files.delete(dir.resolve("commit.json"));check(p.reconcile()==Outcome.UNCHANGED);check(p.publish()==Outcome.COMMITTED)}
        "real journal path failure leaves source unchanged; recovery retries safely"}
    for(error in listOf(5,32,112,1175,1176))case("injected-error-$error-no-change"){dir->
        val(s,c)=fixture(dir);val old=sha(s);CommitProbe(dir).use{p->p.prepare(s,c);check(p.publish(substitute={false to error})==Outcome.UNCHANGED)}
        check(sha(s)==old&&Files.exists(c));"injected adapter error; reconciliation uses evidence"}
    case("injected-1177-original-at-backup"){dir->val(s,c)=fixture(dir);val old=sha(s)
        CommitProbe(dir).use{p->p.prepare(s,c);check(p.publish(substitute={r->Files.move(s,Path.of(r.backup));false to 1177})==Outcome.NEEDS_ATTENTION)
            check(p.reconcile()==Outcome.NEEDS_ATTENTION)}
        check(!Files.exists(s)&&Files.exists(c)&&sha(dir.resolve("original.backup"))==old);"simulated documented intermediate state retained"}
    case("injected-error-after-real-publication"){dir->val(s,c)=fixture(dir)
        CommitProbe(dir).use{p->p.prepare(s,c);check(p.publish(substitute={r->
            check(nativeCommit.ReplaceFileW(win(s),win(c),win(Path.of(r.backup)),0,null,null));false to 1176
        })==Outcome.COMMITTED);check(p.reconcile()==Outcome.COMMITTED)};"real output/backup overrides ambiguous error, no second encode"}
    for(point in listOf("AfterPreparedDb","AfterPreparedMirror","AfterPublishIntentDb","AfterPublishIntentMirror","BeforeNative","AfterNative","AfterPublishedDb","AfterCommittedDb")) {
        case("jvm-crash-$point"){dir->
            val(s,c)=fixture(dir);val old=sha(s);val expected=sha(c)
            val process=ProcessBuilder(childCommand()+listOf("--commit-crash-child",dir.toString(),point))
                .redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile()).start()
            if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();error("Test child timed out")}
            check(process.exitValue()==83)
            CommitProbe(dir).use{p->val outcome=p.reconcile();check(outcome!=Outcome.NEEDS_ATTENTION)
                if(outcome==Outcome.UNCHANGED){check(sha(s)==old);check(p.publish()==Outcome.COMMITTED)}
                else {check(sha(s)==expected);check(p.publish()==Outcome.COMMITTED&&p.nativeCalls==0)}
                check(p.reconcile()==Outcome.COMMITTED)
            }
            check(sha(dir.resolve("original.backup"))==old);"actual JVM halt(83), reopen DB, reconcile and idempotent resume"
        }
    }
    case("restore-does-not-consume-original-backup"){dir->val(s,c)=fixture(dir);val old=sha(s);val encoded=sha(c)
        CommitProbe(dir).use{p->p.prepare(s,c);check(p.publish()==Outcome.COMMITTED)}
        val restoreRoot=dir.resolve("restore");Files.createDirectories(restoreRoot)
        val active=restoreRoot.resolve("current.mp4");Files.copy(s,active)
        val original=restoreRoot.resolve("restore.partial.mp4");Files.copy(dir.resolve("original.backup"),original)
        CommitProbe(restoreRoot).use{p->p.prepare(active,original,allowGrowth=true);check(p.publish()==Outcome.COMMITTED)}
        check(sha(active)==old&&sha(dir.resolve("original.backup"))==old&&sha(restoreRoot.resolve("original.backup"))==encoded)
        "restore keeps original backup and archives replaced compressed version"
    }
    case("different-extension-publish-before-retire"){dir->val(s,c)=fixture(dir);val old=sha(s);val expected=sha(c)
        val target=dir.resolve("中文 原片.mkv");val backup=dir.resolve("retired-original.mp4")
        durable(dir.resolve("rename-intent.json"),"{\"phase\":\"PublishIntent\"}")
        check(nativeCommit.MoveFileExW(win(c),win(target),0));check(sha(target)==expected&&sha(s)==old)
        // Simulate a restart after new target exists; retire only after hash revalidation.
        check(sha(target)==expected&&sha(s)==old);durable(dir.resolve("rename-intent.json"),"{\"phase\":\"RetireIntent\"}")
        check(nativeCommit.MoveFileExW(win(s),win(backup),0));check(sha(backup)==old&&sha(target)==expected)
        "new output verified first, original archived without deletion"
    }
    case("new-target-conflict-does-not-overwrite"){dir->val(s,c)=fixture(dir);val old=sha(s);val target=dir.resolve("existing.mkv")
        Files.writeString(target,"external destination");check(!nativeCommit.MoveFileExW(win(c),win(target),0))
        check(Files.readString(target)=="external destination"&&sha(s)==old&&Files.exists(c));"real no-overwrite MoveFileExW conflict"}
    check(results.none{it["status"]!="PASS"}) { "Commit suite failed; see $report" }
    println("COMMIT_ACCEPTANCE_PASS cases=${results.size}")
}
