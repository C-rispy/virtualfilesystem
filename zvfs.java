import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class zvfs {
    
    static final int HEADER_SIZE = 64;
    static final int MAX_ENTRIES = 32;
    static final int VERSION = 1;
    static final int ALIGNMENT = 64;
    static final byte[] MAGIC = "ZVFSDSK1".getBytes(StandardCharsets.US_ASCII);
    static final int NAME_OFFSET = 0;
    static final int START_OFFSET = 32;
    static final int LENGTH_OFFSET = 36;
    static final int TYPE_OFFSET = 40;
    static final int FLAG_OFFSET = 41;
    static final long SIZE_LIMIT = 4L * 1024 * 1024 *1024; //4gb in bytes is larger than max int value

    public static void main(String[] args) {
        if (args.length < 2){ // because filesystem = filename
            System.out.println("Wrong command");
            return;
        } 
        String command = args[0]; // filename is not sys.arg in java
        String fs = args[1];
        String file = null;
        if (args.length > 2){
            file = args[2];
        }

        switch (command){
            case "mkfs":
                makeFS(fs);
                break;
            case "gifs":
                getInfoFS(fs);
                break;
            case "lsfs":
                lsfs(fs);
                break;
            case "catfs":
                if (args.length < 3){
                    System.out.println("More Arguments needed");
                    return; 
                }
                catfs(fs, file);
                break;
            case "dfrgfs":
                dfrgfs(fs);
                break;
            case "addfs":
                if (args.length < 3){
                    System.out.println("Wrong command");
                    return; // else we crash, since addFS gets run with too little args -> not very user-friendly
                }
                addFS(fs, file);
                break;
            case "getfs":
                if (args.length < 3){
                    System.out.println("Wrong command");
                    return;
                }
                getFS(fs,file);
                break;
            case "rmfs":
                if (args.length < 3){
                    System.out.println("Wrong command");
                    return;
                }
                removeFS(fs,file);
                break;
            default:
                System.out.println("Unknown command: " + command);
        }
    }

    public static void makeFS(String fs) {
        try {
            File fileSystem = new File(fs);
            if (fileSystem.createNewFile()) {   // create new file system, returns false if file(system) already exists
                Path path = Paths.get(fs);
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { // try, such that it closes the writing automatically
                    ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                    headerBuffer.put(MAGIC)
                                .put((byte)VERSION)
                                .put((byte)0) // flag
                                .putShort((short)0) // reserved0
                                .putShort((short)0) // file_count
                                .putShort((short)32) // file_capacity
                                .putShort((short)64) // file_entry_size
                                .putShort((short)0) // reserved1
                                .putInt(64) // file_table_offset
                                .putInt(2112) // data_start_offset
                                .putInt(2112) // next_free_offset
                                .putInt(0) // free_entry_offset
                                .putShort((short)0) // deleted files
                                .put(new byte[26]);   // reserved2 
                    headerBuffer.flip(); // switch from write to read mode
                    channel.position(0);
                    channel.write(headerBuffer);
                    byte[] emptyEntries = new byte [MAX_ENTRIES * 64];// file entries = 32 regions, 64 bytes each
                    ByteBuffer emptyEntryBuffer = ByteBuffer.wrap(emptyEntries).order(ByteOrder.LITTLE_ENDIAN);
                    channel.position(HEADER_SIZE);
                    channel.write(emptyEntryBuffer);
                }
            }else{
                System.out.println("File already exists.");
            }
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    } 


    public static void getInfoFS(String fsFile) {
        Path path = Paths.get(fsFile);
        if ((Files.exists(path)) == false) {
            System.out.println("Error:" + fsFile + "does not exist");
            return;
        } 
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { 
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.position(0); 
            channel.read(headerBuffer);
            headerBuffer.flip();
            headerBuffer.position(12); // position of fileCount
            short fileCount = headerBuffer.getShort(); 
            headerBuffer.position(14); // position of fileCapacity
            short fileCapacity= headerBuffer.getShort(); 
            headerBuffer.position(16); // position of fileEntrySize
            short fileEntrySize = headerBuffer.getShort();
            headerBuffer.position(20); // position of fileTableOffset
            int fileTableOffset = headerBuffer.getInt();
            headerBuffer.position(36); // position of deletedFilesHeader
            short deletedFilesHeader = headerBuffer.getShort();
            int empty_file_entries = fileCapacity - fileCount - deletedFilesHeader;  

            int activeFile = 0;
            int deletedFile = 0;
            int emptyFile = 0;

            for (int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++) { 
                long offset = fileTableOffset + ( (long)entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);  
                channel.position(offset);                 
                channel.read(entryBuffer);
                entryBuffer.flip();
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);
                entryBuffer.position(41);
                byte fileFlags = entryBuffer.get();
                if (!isEmpty(nameBytes)){ 
                    if (fileFlags == 0){ 
                        activeFile = activeFile + 1;
                    }
                    else{
                        deletedFile = deletedFile + 1;
                    }
                }
                else{
                    emptyFile = emptyFile + 1;
                }
            }
            long totalFileSize = channel.size();

            assert deletedFilesHeader == deletedFile : "Number of deleted files not align";
            assert fileCount == activeFile : "Number of active files not align"; 
            assert empty_file_entries == emptyFile : "Number of empty entries not align"; 
            
            System.out.println("File name: " + fsFile);
            System.out.println("Number of files: " + activeFile);
            System.out.println("Free entries: " + emptyFile);
            System.out.println("Deleted files: "+ deletedFile);
            System.out.println("Total size of the file: " + totalFileSize);

        }catch (IOException e) {
            System.out.println("An error occurred while reading the file: " + e.getMessage());
        }
    }

    private static boolean isEmpty(byte[] arr) {
        for (int element : arr) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }
    
    public static void addFS(String fsPath, String srcPath) {
        Path FSPath = Paths.get(fsPath);
        if ((Files.exists(FSPath)) == false) {
            System.out.println("Error: " + FSPath + "does not exist");
            return;
        }

        Path path = Paths.get(srcPath);
        if ((Files.exists(path)) == false) {
            System.out.println("Error: " + path + "does not exist");
        }

        try (FileChannel fsChannel = FileChannel.open(FSPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            fsChannel.position(0);
            fsChannel.read(headerBuffer);
            headerBuffer.flip();
            headerBuffer.position(9); // right after MAGIC, since magic isn't needed
            byte freeSpotFlags = headerBuffer.get();
            short reserved0 = headerBuffer.getShort();
            short fileCount = headerBuffer.getShort();
            short fileCapacity = headerBuffer.getShort();
            short fileEntrySize = headerBuffer.getShort();
            short reserved1 = headerBuffer.getShort();
            int fileTableOffset = headerBuffer.getInt();
            int dataStartOffset = headerBuffer.getInt();
            int nextFreeOffset = headerBuffer.getInt();
            int freeEntryOffset = headerBuffer.getInt();
            short deletedFilesHeader = headerBuffer.getShort();
            byte[] reserved2 = new byte[26];
            headerBuffer.get(reserved2);

            if (fileCount == fileCapacity){
                System.out.println("No more empty entries");
                return;
            }

            String baseFileName = path.getFileName().toString(); 
            byte[] rawFileNameBytes = baseFileName.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(31, rawFileNameBytes.length);
            byte[] fileNameField = new byte[32];  
            System.arraycopy(rawFileNameBytes, 0, fileNameField, 0, copyLen); // new file name

            boolean duplicate = false; // check if file has been added
            for(int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++){
                int entryOffset = fileTableOffset + (entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                fsChannel.position(entryOffset);
                fsChannel.read(entryBuffer); // read 64B into entryBuffer
                entryBuffer.flip(); // prepares cursor for reading
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);
                entryBuffer.position(FLAG_OFFSET);
                byte entryFlag = entryBuffer.get();
                String storedNameStr = new String(nameBytes, StandardCharsets.UTF_8); // convert to string
                storedNameStr = storedNameStr.trim(); // removes nulls and whitespace
                String fileNameStr = new String(fileNameField, StandardCharsets.UTF_8).trim(); // convert file name to string
                if (storedNameStr.equals(fileNameStr) & entryFlag == 0) {
                    duplicate = true;
                    break;
                }
            }
            
            if (duplicate == true){
                System.out.println("Error: File with same name can't be added twice");
                return;
            }

            boolean added = false;
            byte[] data = Files.readAllBytes(Paths.get(srcPath));
            ByteBuffer dataBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int fileSize = data.length;

            long newAlignStart = align(nextFreeOffset); // long, in case we're close to the size limit
            long newEnd = newAlignStart + fileSize;
            if (newEnd > SIZE_LIMIT){
                System.out.println("File could not be added: It would exceed the 4GB size limit!");
                return;
            }

            for(int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++){
                int entryOffset = fileTableOffset + (entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                fsChannel.position(entryOffset);
                fsChannel.read(entryBuffer); // read 64B into entryBuffer
                entryBuffer.flip(); // prepares cursor for reading
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);
                entryBuffer.position(FLAG_OFFSET);
                byte entryFlag = entryBuffer.get();
                if (isEmpty(nameBytes) || entryFlag == 1){  // file entry no name = empty file
                    if (entryFlag == 1){
                        deletedFilesHeader--;
                    }
                    int alignStart = align(nextFreeOffset);  // all data must be aligned to 64 byte blocks
                    if (alignStart > nextFreeOffset){
                        int paddingSize = alignStart - nextFreeOffset;
                        byte [] zeroPadding = new byte[paddingSize];
                        ByteBuffer zeroPaddingBuffer = ByteBuffer.wrap(zeroPadding).order(ByteOrder.LITTLE_ENDIAN);
                        fsChannel.position(nextFreeOffset);
                        fsChannel.write(zeroPaddingBuffer);
                    }
                    fsChannel.position(alignStart);
                    fsChannel.write(dataBuffer);
                    nextFreeOffset = align(alignStart + fileSize);

                    ByteBuffer entryDataBuffer = ByteBuffer.allocate(fileEntrySize).order(ByteOrder.LITTLE_ENDIAN);
                    long timestamp = System.currentTimeMillis() / 1000L;
                    entryDataBuffer.put(fileNameField) // name
                                    .putInt(alignStart)
                                    .putInt(fileSize)
                                    .put((byte)0)
                                    .put((byte)0)
                                    .putShort((short) 0)
                                    .putLong(timestamp)
                                    .put(new byte[12]);
                
                    entryDataBuffer.flip();
                    fsChannel.position(entryOffset);
                    fsChannel.write(entryDataBuffer);
                    fileCount++; // active entries+1

                    //check if free entry still available
                    boolean hasEmptyEntry = false;
                    for (int i = entryIndex+1; i < MAX_ENTRIES; i++){
                        int nextEntryOffset = fileTableOffset + (i * fileEntrySize);
                        ByteBuffer nextEntryBuffer = ByteBuffer.allocate(fileEntrySize);
                        nextEntryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        fsChannel.position(nextEntryOffset); 
                        fsChannel.read(nextEntryBuffer);
                        nextEntryBuffer.flip();
                        byte[] nextNameBytes = new byte[32];
                        nextEntryBuffer.get(nextNameBytes);
                        nextEntryBuffer.position(FLAG_OFFSET);
                        byte nextEntryFlag = nextEntryBuffer.get();
                        if (isEmpty(nextNameBytes) || nextEntryFlag == 1){ 
                            freeSpotFlags = 0;
                            freeEntryOffset = nextEntryOffset;
                            hasEmptyEntry = true;
                            break;
                        }
                    }
                    if(hasEmptyEntry == false){
                        freeSpotFlags = 1;
                        freeEntryOffset = 0;
                    }
                    System.out.println("Successfully added");
                    added = true;
                    break;
                }
            }// if no free entry; can't add file
            if (added == false){
                freeSpotFlags = 1;
                System.out.println("No more free entry, file can't be added");
            }
            ByteBuffer updateHeaderBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            updateHeaderBuffer.put(MAGIC)
                        .put((byte)VERSION)
                        .put((byte)freeSpotFlags) // flag
                        .putShort((short)reserved0) // reserved0
                        .putShort((short)fileCount) // file_count
                        .putShort((short)fileCapacity) // file_capacity
                        .putShort((short)fileEntrySize) // file_entry_size
                        .putShort((short)reserved1) // reserved1
                        .putInt(fileTableOffset) // file_table_offset
                        .putInt(dataStartOffset) // data_start_offset
                        .putInt(nextFreeOffset) // next_free_offset
                        .putInt(freeEntryOffset) // free_entry_offset
                        .putShort((short)deletedFilesHeader) // deleted files
                        .put(reserved2);   // reserved2
            updateHeaderBuffer.flip(); 
            fsChannel.position(0);
            fsChannel.write(updateHeaderBuffer);
            
        }// end try read file system
        catch (IOException e) {
            System.out.println("An error occurred while reading the file: " + e.getMessage());
        }
    }

    public static void  getFS(String fsPath, String srcPath){
        Path FSPath = Paths.get(fsPath);
        if ((Files.exists(FSPath)) == false) {
            System.out.println("Error:" + FSPath + "does not exist");
            return;
        }
        try (FileChannel fsChannel = FileChannel.open(FSPath, StandardOpenOption.READ)){
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            fsChannel.position(0);
            fsChannel.read(headerBuffer);
            headerBuffer.flip();
            headerBuffer.position(9); // right after MAGIC and VERSION, since magic isn't needed
            headerBuffer.get();
            headerBuffer.getShort();
            headerBuffer.getShort();
            headerBuffer.getShort();
            short fileEntrySize = headerBuffer.getShort();
            headerBuffer.getShort();
            int fileTableOffset = headerBuffer.getInt();
            headerBuffer.getInt();
            headerBuffer.getInt();
            headerBuffer.getInt();
            headerBuffer.getShort();
            byte[] reserved2 = new byte[26];
            headerBuffer.get(reserved2);

            for(int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++){
                int entryOffset = fileTableOffset + (entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                fsChannel.position(entryOffset);
                fsChannel.read(entryBuffer); // read 64B into entryBuffer
                entryBuffer.flip(); // prepares cursor for reading
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);

                Path path = Paths.get(srcPath);
                String baseFileName = path.getFileName().toString(); 
                String entryName = new String(nameBytes, StandardCharsets.UTF_8).split("\0")[0];

                if (baseFileName.equals(entryName)){
                    entryBuffer.position(START_OFFSET);
                    int entryStart = entryBuffer.getInt();
                    entryBuffer.position(LENGTH_OFFSET);
                    int entryLength = entryBuffer.getInt();
                    entryBuffer.position(FLAG_OFFSET);
                    byte entryFlag = entryBuffer.get();
                    if (entryFlag == 1){
                        System.out.println("File is deleted");
                        continue;
                    }
                    ByteBuffer entryDataBuffer = ByteBuffer.allocate(entryLength).order(ByteOrder.LITTLE_ENDIAN);
                    fsChannel.position(entryStart);
                    fsChannel.read(entryDataBuffer);
                    entryDataBuffer.flip();

                    Files.write(Paths.get(baseFileName),entryDataBuffer.array());
                    System.out.println("Successfully extracted " + baseFileName);
                    return;
                }
            }
        } // end try read file system
        catch (IOException e){
            System.out.println("An error occured while reading the file: " + e.getMessage());
        }
        System.out.println("File " + srcPath + " not found!");
    }


    public static void removeFS(String fsPath, String srcPath){
        Path FSPath = Paths.get(fsPath);
        if ((Files.exists(FSPath)) == false) {
            System.out.println("Error:" + FSPath + "does not exist");
            return;
        }
        try (FileChannel fsChannel = FileChannel.open(FSPath, StandardOpenOption.READ, StandardOpenOption.WRITE)){
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            fsChannel.position(0);
            fsChannel.read(headerBuffer);
            headerBuffer.flip();
            headerBuffer.position(9);
            byte freeSpotFlags = headerBuffer.get();
            short reserved0 = headerBuffer.getShort();
            short fileCount = headerBuffer.getShort();
            short fileCapacity = headerBuffer.getShort();
            short fileEntrySize = headerBuffer.getShort();
            short reserved1 = headerBuffer.getShort();
            int fileTableOffset = headerBuffer.getInt();
            int dataStartOffset = headerBuffer.getInt();
            int nextFreeOffset = headerBuffer.getInt();
            int freeEntryOffset = headerBuffer.getInt();
            short deletedFilesHeader = headerBuffer.getShort();
            byte[] reserved2 = new byte[26];
            headerBuffer.get(reserved2);

            for (int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++){
                int entryOffset = fileTableOffset + (entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                fsChannel.position(entryOffset);
                fsChannel.read(entryBuffer); // read 64B into entryBuffer
                entryBuffer.flip(); // prepares cursor for reading
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);

                Path path = Paths.get(srcPath);
                String baseFileName = path.getFileName().toString(); 
                String entryName = new String(nameBytes, StandardCharsets.UTF_8).split("\0")[0];

                if (baseFileName.equals(entryName)){
                    entryBuffer.position(FLAG_OFFSET);
                    byte entryFlag = entryBuffer.get();
                    if (entryFlag == 1){
                        System.out.println("Error:" + FSPath + "is deleted!");
                        return;
                    }

                    ByteBuffer newEntryBuffer = ByteBuffer.allocate(fileEntrySize).order(ByteOrder.LITTLE_ENDIAN);
                    newEntryBuffer.put(nameBytes);
                    newEntryBuffer.putInt(entryBuffer.getInt(START_OFFSET));
                    newEntryBuffer.putInt(entryBuffer.getInt(LENGTH_OFFSET));
                    newEntryBuffer.put(entryBuffer.get(TYPE_OFFSET));
                    newEntryBuffer.put((byte) 1); // new flag: deleted = 1
                    newEntryBuffer.putShort(entryBuffer.getShort(TYPE_OFFSET+2));
                    newEntryBuffer.putLong(entryBuffer.getLong(TYPE_OFFSET+4));
                    newEntryBuffer.put(new byte[12]);

                    fileCount--; deletedFilesHeader++;

                    newEntryBuffer.flip();
                    fsChannel.position(entryOffset);
                    fsChannel.write(newEntryBuffer);
                    
                    freeEntryOffset = entryOffset;
                    freeSpotFlags = 0;
                    System.out.println("Successfully deleted " + baseFileName);

                    ByteBuffer newHeaderBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                    newHeaderBuffer.put(MAGIC)
                        .put((byte) VERSION)
                        .put(freeSpotFlags)
                        .putShort(reserved0)
                        .putShort(fileCount)
                        .putShort(fileCapacity)
                        .putShort(fileEntrySize)
                        .putShort(reserved1)
                        .putInt(fileTableOffset)
                        .putInt(dataStartOffset)
                        .putInt(nextFreeOffset)
                        .putInt(freeEntryOffset)
                        .putShort(deletedFilesHeader)
                        .put(reserved2);

                    newHeaderBuffer.flip();
                    fsChannel.position(0);
                    fsChannel.write(newHeaderBuffer);
                    return; // exits try block, so print below is not executed if file is found
                }                
            }
            System.out.println("File " + srcPath + " not found!");
        } // end try read file system
        catch (IOException e){
            System.out.println("An error occured while reading the file: " + e.getMessage());
        }
    }

    public static int align(int offset) {
        int remainder = offset % ALIGNMENT;
        if (remainder != 0){
            return offset  + (ALIGNMENT - remainder);
        }
        else{
            return offset;
        }
    }
    public static void lsfs(String fsFile){
        Path path = Paths.get(fsFile);
        if ((Files.exists(path)) == false){
            System.out.println("Error: " + fsFile + "does not exist");
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)){
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.position(0);
            channel.read(headerBuffer);
            headerBuffer.flip();
            
            headerBuffer.position(16);
            short fileEntrySize = headerBuffer.getShort();

            headerBuffer.position(20);
            int fileTableOffset = headerBuffer.getInt();

            for (int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex ++){
                long offset = fileTableOffset + ( (long)entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(offset);
                channel.read(entryBuffer);
                entryBuffer.flip();
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);
                entryBuffer.position(36);
                int Size = entryBuffer.getInt();
                entryBuffer.position(41);
                byte flag = entryBuffer.get();
                entryBuffer.position(44);
                long rawTimestamp = entryBuffer.getLong();

                if(isEmpty(nameBytes)){
                    continue;
                }
                if(flag == 1){
                    continue;
                }
                String Name = new String(nameBytes, StandardCharsets.UTF_8).split("\0")[0];
                String Timestamp = new Date(rawTimestamp * 1000L).toString();
                System.out.println("File: " + Name + ", Size: " + Size + ", Created: " + Timestamp);
            }


        }catch (IOException e) {
            System.out.println("An error occured while reading the file: " + e.getMessage());
        }

    }
    public static void catfs(String fsFile, String File){
        Path path = Paths.get(fsFile);
        if ((Files.exists(path)) == false){
            System.out.println("Error: " + fsFile + "does not exist");
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)){
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.position(0);
            channel.read(headerBuffer);
            headerBuffer.flip();
            
            headerBuffer.position(16);
            short fileEntrySize = headerBuffer.getShort();

            headerBuffer.position(20);
            int fileTableOffset = headerBuffer.getInt();

            for(int entryIndex = 0; entryIndex < MAX_ENTRIES; entryIndex++){
                long offset = fileTableOffset + ( (long)entryIndex * fileEntrySize);
                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(offset);
                channel.read(entryBuffer);
                entryBuffer.flip();
                byte[] nameBytes = new byte[32];
                entryBuffer.get(nameBytes);
                entryBuffer.position(41);
                byte flag = entryBuffer.get();
                String Name = new String(nameBytes, StandardCharsets.UTF_8).split("\0")[0];
                
                if(Name.equals(File) && flag == 0){
                    entryBuffer.position(32);
                    int startOffset = entryBuffer.getInt();
                    entryBuffer.position(36);
                    int fileLength = entryBuffer.getInt();
                    ByteBuffer dataBuffer = ByteBuffer.allocate(fileLength);
                    channel.position(startOffset);
                    channel.read(dataBuffer);
                    dataBuffer.flip();
                    String Data = new String(dataBuffer.array(), StandardCharsets.UTF_8);
                    System.out.println(Data);
                    return;
                }
            }

    }catch (IOException e) {
            System.out.println("An error occured while reading the file: " + e.getMessage());
        }   

    }

    public static void dfrgfs(String fsFile){
        Path path = Paths.get(fsFile);
        if ((Files.exists(path)) == false) {
            System.out.println("Error:" + fsFile + "does not exist");
            return;
        }
        List<byte[]> namesList = new ArrayList<>();
        List<byte[]> dataList = new ArrayList<>();
        List<Long> timeList = new ArrayList<>();

        int delCount = 0;

        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)){
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.position(0);
            channel.read(headerBuffer);
            headerBuffer.flip();
            
            byte[] magic = new byte[8];
            headerBuffer.get(magic);
            byte version = headerBuffer.get();
            headerBuffer.get();
            short reserved0 = headerBuffer.getShort();
            headerBuffer.getShort();
            short fileCapacity = headerBuffer.getShort();
            short fileEntrySize = headerBuffer.getShort();
            short reserved1 = headerBuffer.getShort();
            int fileTableOffset = headerBuffer.getInt();
            int dataStartOffset = headerBuffer.getInt();
            int endOffset = headerBuffer.getInt();

            for (int i = 0; i < MAX_ENTRIES; i++){
                long offset = fileTableOffset + ((long) i * fileEntrySize);

                ByteBuffer entryBuffer = ByteBuffer.allocate(fileEntrySize);
                entryBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(offset);
                channel.read(entryBuffer);
                entryBuffer.flip();

                byte[] name = new byte[32];
                entryBuffer.get(name);
                entryBuffer.position(32);
                int startOffset = entryBuffer.getInt();
                int fileLength = entryBuffer.getInt();
                entryBuffer.position(41);
                byte flag = entryBuffer.get();
                entryBuffer.position(44);
                long time = entryBuffer.getLong();

                if(isEmpty(name)) {
                    continue;
                }

                if (flag == 1){
                    delCount++;
                    continue;
                }

                ByteBuffer dataBuffer = ByteBuffer.allocate(fileLength);
                channel.position(startOffset);
                channel.read(dataBuffer);
                dataBuffer.flip();
                 
                namesList.add(name);
                dataList.add(dataBuffer.array());
                timeList.add(time);
            }
            
            int dataOffset = dataStartOffset;
            byte[] emptyTable = new byte[MAX_ENTRIES * fileEntrySize];
            channel.position(fileTableOffset);
            channel.write(ByteBuffer.wrap(emptyTable));

            for (int i = 0; i < namesList.size(); i++){
                byte[] currentName = namesList.get(i);
                byte[] currentData = dataList.get(i);
                long currentTime = timeList.get(i);
                
                int position = align(dataOffset);
                
                if (position > dataOffset){
                    byte[] padding = new byte[position - dataOffset];
                    channel.position(dataOffset);
                    channel.write(ByteBuffer.wrap(padding));
                }

                channel.position(position);
                channel.write(ByteBuffer.wrap(currentData));
                long fileEntryOffset = fileTableOffset + ((long) i * fileEntrySize);
                
                ByteBuffer reEntry = ByteBuffer.allocate(fileEntrySize);
                reEntry.order(ByteOrder.LITTLE_ENDIAN);

                reEntry.put(currentName);
                reEntry.putInt(position);
                reEntry.putInt(currentData.length);
                reEntry.put((byte) 0);
                reEntry.put((byte) 0);
                reEntry.putShort((short) 0);
                reEntry.putLong(currentTime);
                reEntry.put(new byte[12]);

                reEntry.flip();
                channel.position(fileEntryOffset);
                channel.write(reEntry);

                dataOffset = align(position + currentData.length);
            }
            int freedBytes = endOffset - dataOffset;
            int fileCount = namesList.size();
            int newNextFreeOffset = dataOffset;
            int newFreeEntryOffset;
            byte newFlag;

            if(fileCount < 32){
                newFreeEntryOffset = fileTableOffset + (fileCount * fileEntrySize);
                newFlag = 0;
            }  else{
                newFreeEntryOffset = 0;
                newFlag = 1;
            }
            ByteBuffer newHeader = ByteBuffer.allocate(HEADER_SIZE);
            newHeader.order(ByteOrder.LITTLE_ENDIAN);

            newHeader.put(magic);
            newHeader.put(version);
            newHeader.put(newFlag);
            newHeader.putShort(reserved0);
            newHeader.putShort((short) fileCount);
            newHeader.putShort(fileCapacity);
            newHeader.putShort(fileEntrySize);
            newHeader.putShort(reserved1);
            newHeader.putInt(fileTableOffset);
            newHeader.putInt(dataStartOffset);
            newHeader.putInt(newNextFreeOffset);
            newHeader.putInt(newFreeEntryOffset);
            newHeader.putShort((short) 0);
            newHeader.put(new byte[26]);

            newHeader.flip();
            channel.position(0);
            channel.write(newHeader);

            System.out.println("Files removed: " + delCount);
            System.out.println("Byted freed: "+ freedBytes);

        }catch (IOException e) {
            System.out.println("An error occured while reading the file: " + e.getMessage());
        } 
    }
}