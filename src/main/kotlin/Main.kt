import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread

class ChunkBuffer(internal val capacity: Int) {
    internal val buffer = ByteArray(capacity)
    @Volatile
    var blockedWrite = false
        internal set
    @Volatile
    internal var chunkSequence = 0
    @Volatile
    internal var readSequence = 0
    internal val writeSequence: Int
        get() = chunkSequence * capacity - 1

    fun poll(): Byte? {
        val isEmpty = writeSequence < readSequence
        if (!isEmpty) {
            val nextValue: Byte = buffer[readSequence % capacity]
            readSequence++
            return nextValue
        }
        return null
    }
}

fun ChunkBuffer.offerChunk(input: InputStream): Boolean {
    val size = writeSequence - readSequence + 1
    val isFull = size > 0
    if (!isFull && !blockedWrite) {
        input.read(buffer, 0, capacity)
        chunkSequence++
        return true
    }
    return false
}
fun ChunkBuffer.pollRest(): List<Byte> {
    val rest = List<Byte?>(capacity) { poll() }
    return rest.filterNotNull()
}

fun main() {
    val client = Socket("npm.mipt.ru", 9048)
    val output = client.outputStream
    output.write("HELLO\n".toByteArray())
    val input = client.inputStream

    fun ChunkBuffer.scrollUntil(matchString: String){
        val match = matchString.toByteArray()
        var matched = 0
        while (matched != match.size){
            val nextByte = this.poll() ?: continue
            if (nextByte == match[matched]) {
                matched += 1
            } else {
                matched = 0
            }
        }
        blockedWrite = true
    }

    val buffer = ChunkBuffer(4)
    val writeThread = thread {
        while(!buffer.blockedWrite) { // waiting for halt from next thread
            buffer.offerChunk(input)
        }
    }
    val readThread =  thread {
        buffer.scrollUntil("RES") // set blockedWrite when done
    }
    readThread.join()
    writeThread.join()

    val restBufferedBytes = buffer.pollRest()
    val resBytes = ByteArray(135)
    if(restBufferedBytes.isEmpty()){ // offset = 1
        input.read(resBytes, 0, 1)
    } else { // offset = rest.size
        restBufferedBytes.forEachIndexed { index, byte ->
            resBytes[index] = byte
        }
    }

    val nBytes = resBytes[0].toInt()
    val offset = maxOf(1, restBufferedBytes.size)
    input.read(resBytes, offset, nBytes - offset + 3) // nBytes:msgLen - (offset:rest.size - 1:res[0]) + 2:"0\n"
    val sumOfBytes = resBytes.sliceArray(1 until nBytes + 1).sumOf { it.toUByte().toInt() }
    output.write("SUM$sumOfBytes\n".toByteArray())
    val response = input.bufferedReader().readLine()
    println(response)

    input.close()
    output.close()
    client.close()
}