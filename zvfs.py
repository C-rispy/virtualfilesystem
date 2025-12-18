import sys
import struct
import os 
import time

HEADER_SIZE = 64
MAX_ENTRIES = 32
MAGIC = b"ZVFSDSK1"
VERSION = 1

HEADER_FORMAT = "<8sBBhhhhhiiiih26s"
FILE_FORMAT = "<32siiBBhQ12s"

SIZE_LIMIT = 4*1024*1024*1024 # 4GB filesystem size limit

ALIGNMENT = 64

def align(offset):
    remainder = offset % ALIGNMENT
    if remainder != 0:
        return offset + (ALIGNMENT - remainder)
    return offset
    
def makeFS(fs):
    if os.path.exists(fs):
        print(f"Error: {fs} already exists")
        return
    header = struct.pack(
        HEADER_FORMAT,
        MAGIC,             
        VERSION,
        0, # flag
        0, # reserved0
        0, # file_count
        32, # file_capacity
        64, # file_entry_size
        0, # reserved1
        64, # file_table_offset
        2112, # data_start_offset
        2112, # next_free_offset
        0, # free_entry_offset
        0, # deleted files
        b"\x00"  # reserved2   
    ) 

    with open(fs, "wb") as f:
        f.write(header)
        emptyEntries = b"\x00" * (32 * 64) # file entries = 32 regions, 64 bytes each
        f.write(emptyEntries)

def getInfoFS(fs_file):
    if not os.path.exists(fs_file):
        print(f"Error: {fs_file} doesn't exist")
        return  
    with open(fs_file, 'rb') as f:
        chunk = f.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)  
        #print("Header unpacked:", header_unpack) ##for debugging purpose; need to be removed!
        files_num = header_unpack[4] #file_count
        file_capacity = header_unpack[5] #file_capacity
        file_entry_size = header_unpack[6] #file_entry_size
        file_table_offset = header_unpack[8] #file_table_offset
        deleted_files_num = header_unpack[12] #deleted_files  
        empty_file_entries = file_capacity - files_num - deleted_files_num     

        active_file = 0
        deleted_file = 0
        empty_file = 0
        for entry_index in range(MAX_ENTRIES):
                offset = file_table_offset + (entry_index*file_entry_size)
                f.seek(offset)
                chunk = f.read(file_entry_size)
                file_unpack = struct.unpack(FILE_FORMAT, chunk)
                if file_unpack[0] != (b"\x00" * 32): #name
                    if file_unpack[4] == 0: #flag
                        active_file = active_file + 1 # name is not all 0 & flag = 0
                    else:
                        deleted_file = deleted_file + 1 # name is not all 0 & flag = 1
                else:
                    empty_file = empty_file + 1 # name is all 0
        total_file_size = os.path.getsize(fs_file)
    
    if deleted_files_num != deleted_file: # changed to Error prints, so that program doesn't crash
         print("Error: The number of deleted files does not match")
         return
    
    if files_num != active_file:
         print("Error: The number of active files does not match")
         return
    
    if empty_file_entries != empty_file:
         print("Error: The number of empty entry does not match")
         return
    
    print("File name: ", fs_file)
    print("Number of files: ", active_file)
    print("Free entries: ", empty_file)
    print("Deleted files: ", deleted_file)
    print("Total size of the file: ", total_file_size)

def addFS(fs_path, src_path):
    if not os.path.exists(fs_path):
        print(f"Error: {fs_path} doesn't exist!")
        return
    
    if not os.path.exists(src_path): # avoids problems with nonexistent files
        print(f"Error: {src_path} doesn't exist!")
        return

    with open(fs_path, "r+b") as fs_file: # open for reading AND writing in binary
        chunk = fs_file.read(HEADER_SIZE) # read header in fs (first 64 (HEADER_SIZE) bytes)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)
        free_spot_flag = header_unpack[2]
        reserved0 = header_unpack[3]
        file_count = header_unpack[4]
        file_capacity = header_unpack[5]
        file_entry_size = header_unpack[6] 
        reserved1 = header_unpack[7]
        file_table_offset = header_unpack[8]
        data_start_offset = header_unpack[9]
        next_free_offset = header_unpack[10]
        free_entry_offset = header_unpack[11]
        deleted_files = header_unpack[12]
        reserved2 = header_unpack[13]

        if (file_count == file_capacity):
            print("No more empty entries")
            return
        
        with open(src_path, "rb") as src_file:
                        data = src_file.read() # read the file that need to be added
                        file_size = len(data)
        
        align_start = align(next_free_offset)
        new_end = align_start + file_size
        if new_end > SIZE_LIMIT:
            print(f"File could not be added: It would exceed the {SIZE_LIMIT}B size limit!")
            return


        raw_name = os.path.basename(src_path) # name of the file that need to be added
        name_bytes = raw_name.encode('utf-8')[:31]
        new_entry_name = name_bytes + b"\x00" # new entry name

        duplicate = False # check if the file has been added
        for entry_index in range(MAX_ENTRIES):
            entry_offset = file_table_offset + (entry_index*file_entry_size)
            fs_file.seek(entry_offset) # seek the file entry in fs / moves file pointer to start of new entry
            chunk = fs_file.read(file_entry_size) # reads 64 bytes of code (entire file entry)
            file_entry_unpack = struct.unpack(FILE_FORMAT, chunk)
            entry_name = file_entry_unpack[0]
            flag = file_entry_unpack[4]
            stored_name = entry_name.split(b'\x00')[0] # remove the 0 padding
            if (stored_name == name_bytes and flag == 0): # entry name same with the name of the file to be added
                duplicate = True
                break
        
        if duplicate == True:
            print("Error: File with same name can't be added twice")
            return

        for entry_index in range(MAX_ENTRIES):
                entry_offset = file_table_offset + (entry_index*file_entry_size)
                fs_file.seek(entry_offset) # seek the file entry in fs / moves file pointer to start of new entry
                chunk = fs_file.read(file_entry_size) # reads 64 bytes of code (entire new file)
                file_entry_unpack = struct.unpack(FILE_FORMAT, chunk)
                entry_flag = file_entry_unpack[4]
                if file_entry_unpack[0] == (b"\x00" * 32) or entry_flag == 1: # file entry no name = empty file, flag = 1 -> file can be overwritten (UNSURE IF WE'RE SUPPOSED TO DO THAT)
                    if entry_flag == 1:
                        deleted_files -= 1
                    align_start = align(next_free_offset) # all data must be aligned to 64 byte blocks
                    if align_start > next_free_offset:
                        padding_size = align_start - next_free_offset
                        fs_file.seek(next_free_offset)  # the zeropadding needs to start at the next_free_offset
                        fs_file.write(b"\x00" * padding_size) # fill in with 0 bytes to reach 64 byte blocks
                    fs_file.seek(align_start) # seek the correct position to start writing file data
                    fs_file.write(data)
                    next_free_offset = align(align_start + file_size)
                    
                    entry_start = align_start
                    entry_length = len(data)
                    entry_type = 0
                    entry_flag = 0
                    entry_reserved0 = 0
                    entry_timestamp = int(time.time())
                    entry_reserved1 = b"\x00" * 12
                    entry_packed = struct.pack(
                        FILE_FORMAT,
                        new_entry_name,
                        entry_start,
                        entry_length,
                        entry_type,
                        entry_flag,
                        entry_reserved0,
                        entry_timestamp,
                        entry_reserved1
                    )
                    fs_file.seek(entry_offset) # seek the correct position to start writing file entry
                    fs_file.write(entry_packed)
                    file_count += 1 # active entries+1

                    # check if free entry still available
                    for i in range(entry_index+1, MAX_ENTRIES):
                        next_entry_offset = file_table_offset + (i * file_entry_size)
                        fs_file.seek(next_entry_offset)
                        next_entry = fs_file.read(file_entry_size)
                        next_file_unpack = struct.unpack(FILE_FORMAT, next_entry)
                        if next_file_unpack[0] == (b"\x00" * 32) or next_file_unpack[4]==1: # enables overwriting files deleted with rmfs, without needing defragmenting
                            free_spot_flag = 0
                            free_entry_offset = next_entry_offset
                            break
                    else:
                        free_spot_flag = 1
                        free_entry_offset = 0
                    print("Successfully added")
                    break
    
        # no free entry; can't add the file
        else:
            free_spot_flag = 1
            print("No more free entry, file can't be added")

        header = struct.pack(
            HEADER_FORMAT,
            MAGIC,             
            VERSION,
            free_spot_flag,
            reserved0,
            file_count, 
            file_capacity, 
            file_entry_size,
            reserved1, 
            file_table_offset,
            data_start_offset,
            next_free_offset,
            free_entry_offset,
            deleted_files, 
            reserved2           
        ) 
        fs_file.seek(0)
        fs_file.write(header)
        return

def getFS(fs_path, src_path):
    if not os.path.exists(fs_path):
        print(f"Error: {fs_path} doesn't exist!")
        return
    
    with open(fs_path, "rb") as fs_file:
        chunk = fs_file.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)
        file_entry_size = header_unpack[6]
        file_table_offset = header_unpack[8]
        
        for entry_index in range(MAX_ENTRIES):
               entry_offset = file_table_offset + (entry_index*file_entry_size)
               fs_file.seek(entry_offset)

               chunk = fs_file.read(file_entry_size)
               file_entry_unpack = struct.unpack(FILE_FORMAT, chunk)
               if file_entry_unpack[0].split(b"\x00")[0] == src_path.encode("utf-8"):
                    entry_start = file_entry_unpack[1]
                    entry_length = file_entry_unpack[2]
                    entry_flag = file_entry_unpack[4]

                    if entry_flag == 1:
                        print("File is deleted")
                        continue # not break/return, since later another file could be with the same name, where flag = 0

                    fs_file.seek(entry_start)
                    entry_data = fs_file.read(entry_length)
                    with open(src_path,"wb") as output:
                        output.write(entry_data)
                    
                    print(f"Successfully extracted {src_path} to disk")
                    return
               
        print(f"File {src_path} not found")


def removeFS(fs_path, src_path):
    if not os.path.exists(fs_path):
        print(f"Error: {fs_path} doesn't exist!")
        return
    
    with open(fs_path, "r+b") as fs_file:
        chunk = fs_file.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)

        free_spot_flag = header_unpack[2]
        reserved0 = header_unpack[3]
        file_count = header_unpack[4]
        file_capacity = header_unpack[5]
        file_entry_size = header_unpack[6] 
        reserved1 = header_unpack[7]
        file_table_offset = header_unpack[8]
        data_start_offset = header_unpack[9]
        next_free_offset = header_unpack[10]
        free_entry_offset = header_unpack[11]
        deleted_files = header_unpack[12]
        reserved2 = header_unpack[13]

        for entry_index in range(MAX_ENTRIES):
            entry_offset = file_table_offset + (entry_index*file_entry_size)
            fs_file.seek(entry_offset)

            chunk = fs_file.read(file_entry_size)
            file_entry_unpack = struct.unpack(FILE_FORMAT, chunk)
            if file_entry_unpack[0].split(b"\x00")[0] == src_path.encode("utf-8"):
                entry_flag = file_entry_unpack[4]
                if entry_flag == 1:
                    print(f"File with name '{src_path}' is already deleted!")
                    return
                
                new_entry = struct.pack(
                    FILE_FORMAT,
                    file_entry_unpack[0],
                    file_entry_unpack[1],
                    file_entry_unpack[2],
                    file_entry_unpack[3],
                    1,
                    file_entry_unpack[5],
                    file_entry_unpack[6],
                    file_entry_unpack[7]
                )

                fs_file.seek(entry_offset)
                fs_file.write(new_entry)

                file_count -= 1
                deleted_files += 1

                free_entry_offset = entry_offset
                free_spot_flag = 0
                print(f"Successfully deleted file with name '{src_path}'")
                break
        else:
            print(f"File with name {src_path} not found!")
            return
        
        new_header = struct.pack(
            HEADER_FORMAT,
            MAGIC,     
            VERSION,
            free_spot_flag,
            reserved0,
            file_count,
            file_capacity,
            file_entry_size,
            reserved1,
            file_table_offset,
            data_start_offset,
            next_free_offset,
            free_entry_offset,
            deleted_files,
            reserved2
        )

        fs_file.seek(0)
        fs_file.write(new_header)            
                
def lsfs(fs_file):
    assert os.path.exists(fs_file), f"Error: Filesystem {fs_file} does not exist"

    with open(fs_file, 'rb') as file:
        file.seek(0)
        chunk = file.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)
        file_table_offset = header_unpack[8]
        file_entry_size = header_unpack[6]


        for i in range(MAX_ENTRIES):
            offset = file_table_offset + (i * file_entry_size)

            file.seek(offset)
            entry_chunk = file.read(file_entry_size)
            entry = struct.unpack(FILE_FORMAT, entry_chunk)
            flag = entry[4]
            if flag == 1:
                continue

            raw_name = entry[0]
            if raw_name == (b'\x00' * 32):   #in case its empty space
                continue

            file_size = entry[2]
            raw_timestamp = entry[6]

            file_name = raw_name.decode('utf-8').rstrip('\x00')
            file_timestamp = time.ctime(raw_timestamp)

            print(f'File: {file_name}, Size: {file_size}, Created: {file_timestamp}')

def catfs(fs_file, file_in_fs):
    assert os.path.exists(fs_file), f"Error: Filesystem {fs_file} does not exist"

    with open(fs_file, 'rb') as file:
        file.seek(0)
        chunk = file.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)
        file_table_offset = header_unpack[8]
        file_entry_size = header_unpack[6]

        for i in range(MAX_ENTRIES):
            offset = file_table_offset + i * file_entry_size
            file.seek(offset)
            entry_chunk = file.read(file_entry_size)
            entry = struct.unpack(FILE_FORMAT, entry_chunk)
            
            raw_name = entry[0]
            start_offset = entry[1]
            file_length = entry[2]
            flag = entry[4]
            file_name = raw_name.decode('utf-8').rstrip('\x00')
            
            if file_in_fs == file_name and flag == 0:
                file.seek(start_offset)
                data = file.read(file_length)
                print(data.decode('utf-8'))
                return
        
        print(f"Error: {file_in_fs} does not exist inside {fs_file}.")

def dfrgfs(fs_file):
    assert os.path.exists(fs_file), f"Error: {fs_file} does not exist"

    files = []
    del_count = 0


    with open(fs_file, 'r+b') as file:
        file.seek(0)
        chunk = file.read(HEADER_SIZE)
        header_unpack = struct.unpack(HEADER_FORMAT, chunk)
        file_table_offset = header_unpack[8]
        file_entry_size = header_unpack[6]
        data_offset = header_unpack[9]
        next_free_offset = header_unpack[10]

        for i in range(MAX_ENTRIES):
            offset = file_table_offset + i * file_entry_size
            file.seek(offset)
            entry_chunk = file.read(file_entry_size)
            entry = struct.unpack(FILE_FORMAT, entry_chunk)

            raw_name = entry[0]
            start_offset = entry[1]
            file_length = entry[2]
            flag = entry[4]
            created = entry[6]

            if raw_name == (b'\x00' * 32):
                continue

            if flag == 1:
                del_count += 1
                continue


            file.seek(start_offset)
            file_data = file.read(file_length)
            files.append((raw_name, file_data, created))


        file.seek(file_table_offset)
        file.write(b'\x00' * (MAX_ENTRIES * file_entry_size))

        for i, (name, data, created) in enumerate(files):
            position = align(data_offset)

            if position > data_offset:
                file.seek(data_offset)
                file.write(b'\x00' * (position - data_offset))

            file.seek(position)
            file.write(data)

            file_entry_offset = file_table_offset + i * file_entry_size

            re_entry = struct.pack(
                FILE_FORMAT,
                name,
                position,
                len(data),
                0,
                0,
                0,
                created,
                b'\x00' * 12
                )

            file.seek(file_entry_offset)
            file.write(re_entry)

            data_offset = align(position + len(data))

        freed_bytes = next_free_offset - data_offset
        file_count = len(files)
        new_next_free_offset = data_offset
        if file_count < 32:
            new_free_entry_offset = file_table_offset + (file_count*file_entry_size)
            new_flag = 0
        else:
            new_free_entry_offset = 0
            new_flag = 1

        header = struct.pack(
            HEADER_FORMAT,
            header_unpack[0],
            header_unpack[1],
            new_flag,
            header_unpack[3],
            file_count,
            header_unpack[5],
            header_unpack[6],
            header_unpack[7],
            header_unpack[8],
            header_unpack[9],
            new_next_free_offset,
            new_free_entry_offset,
            0,
            header_unpack[13]
            )

        file.seek(0)
        file.write(header)
        print(f'Files removed: {del_count}')
        print(f'Bytes freed: {freed_bytes}')

def main():
    if len(sys.argv) < 3:
        print("Wrong command")
        return
    command = sys.argv[1]
    fs = sys.argv[2]
    
    if command == "mkfs":
        makeFS(fs)
    elif command == "gifs":
        getInfoFS(fs)
    elif command == "lsfs":
        lsfs(fs)
    elif command == "dfrgfs":
         dfrgfs(fs)
    elif command == "addfs":
        if len(sys.argv) < 4:
            print("Wrong command, or not enough arguments passed")
            return
        file = sys.argv[3]
        addFS(fs, file)
    elif command == "getfs":
        if len(sys.argv) < 4:
            print("Wrong command, or not enough arguments passed")
            return
        file = sys.argv[3]
        getFS(fs, file)
    elif command == "rmfs":
        if len(sys.argv) < 4:
            print("Wrong command, or not enough arguments passed")
            return
        file = sys.argv[3]
        removeFS(fs, file)
    elif command == "catfs":
        if len(sys.argv) < 4:
            print("Wrong command, not enough arguments passed")
            return
        file = sys.argv[3]
        catfs(fs, file)
       
if __name__ == "__main__":
    main()