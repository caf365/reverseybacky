package edu.sandiego.bcl;

import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.regex.Pattern;
import javafx.util.Pair;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Stack;
import javafx.scene.control.Tab;

/**
 * A class representing the state of the machine, namely its register file and
 * memory.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
public class MachineState {

    /**
     * The register file.
     */
    private Map<String, RegisterState> registers;

    /**
     * The machine's memory.
     */
    private List<StackEntry> memory;

    /**
     * The state's tabs.
     */
    private List<Tab> tabList;

    /**
     * The status flags (i.e. condition codes).
     */
    private Map<String, Boolean> statusFlags;

    /**
     * The rip register.
     */
    private int rip;
    
    /**
     * Number of functions on call stack
     */
    private int callStackSize;

    /**
     * Create a new state with all registers (except %rsp) initialized to 0 but
     * no memory initialization. %rsp is initialized to 0x7FFFFFFF.
     */
    public MachineState() {
        this.registers = new HashMap<String, RegisterState>();
        this.memory = new ArrayList<StackEntry>();
        this.tabList = new ArrayList<Tab>();
        this.statusFlags = new HashMap<String, Boolean>();
        this.rip = 0;
        this.callStackSize = 0;

        String[] regNames = {"rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"};
        for (String s : regNames) {
            registers.put(s, new RegisterState(new byte[8], -1));
        }

        // initialize RSP to 0x7FFFFFFFFFFFFFF8
        long initRSP = 1 << 30;
        initRSP <<= 30;
        initRSP <<= 3;
        initRSP = ~initRSP;
        initRSP -= 7;
        registers.put("rsp", new RegisterState(ByteBuffer.allocate(8).putLong(initRSP).array(), -1));

        String[] flagNames = {"zf", "sf", "of", "cf"};
        for (String s : flagNames) {
            statusFlags.put(s, false);
        }
    }

    public MachineState(Map<String, RegisterState> reg, List<StackEntry> mem, List<Tab> tList, Map<String, Boolean> flags, int RIP, int cStack) {
        this.registers = reg;
        this.memory = mem;
        this.tabList = tList;
        this.statusFlags = flags;
        this.rip = RIP;
        this.callStackSize = cStack;
    }

    // Getters for the status flags
    public boolean getCarryFlag() {
        return this.statusFlags.get("cf");
    }

    public boolean getOverflowFlag() {
        return this.statusFlags.get("of");
    }

    public boolean getZeroFlag() {
        return this.statusFlags.get("zf");
    }

    public boolean getSignFlag() {
        return this.statusFlags.get("sf");
    }

    /**
     * Create a new MachineState based on the current state but with an updated
     * value for a memory address.
     *
     * @param newValueForStack The new value of the given memory address.
     * @param newValueStartingAddress The starting (i.e. lowest) address that will be changed.
     * @param newValueSize The number of bytes to write to memory.
     * @param flagsForClone The condition flagsForClone to modify for the new state.
     * @param incrementRIP Whether to increment RIP or not.
     * @return A new state that is the same as the current but with new binding
     * from given address to given val.
     * @throws edu.sandiego.bcl.x86RuntimeException
     */
    public MachineState cloneWithUpdatedMemory(Optional<BigInteger> newValueForStack, 
            long newValueStartingAddress, int newValueSize,
            Map<String, Boolean> flagsForClone,
            boolean incrementRIP) throws x86RuntimeException {
        
        List<StackEntry> stackForClone = this.memory;
        Map<String, RegisterState> registersForClone = this.registers;

        if (newValueForStack.isPresent()) {
            // Limit writes to only valid memory locations (i.e. the stack).
            if (!isValidMemoryAccess(newValueStartingAddress, newValueSize)) {
                throw new x86RuntimeException("Invalid write to 0x" 
                        + Long.toHexString(newValueStartingAddress).toUpperCase());
            }
            
            // Enforce Intel-recommended alignments.
            if (newValueStartingAddress % newValueSize != 0) {
                throw new x86RuntimeException("Unaligned memory access");
            }
            
            stackForClone = new ArrayList<>(this.memory);
            
            long newStartAddr = newValueStartingAddress;
            long newEndAddr = newValueStartingAddress + newValueSize - 1;
            
            // Check for overlap with existing entries and handle accordingly.
            for (StackEntry entry : this.memory) {
                
                long entryStartAddr = entry.getStartAddress();
                long entryEndAddr = entry.getEndAddress();
                
                if (Long.compareUnsigned(newStartAddr, entryStartAddr) <= 0 
                        && Long.compareUnsigned(newEndAddr, entryEndAddr) >= 0) {
                    // The new StackEntry completely ensconces the old, so we'll
                    // simply remove the old one
                    stackForClone.remove(entry);
                } else if (Long.compareUnsigned(newStartAddr, entryStartAddr) > 0 
                        && Long.compareUnsigned(newEndAddr, entryEndAddr) < 0) {
                    // The new entry is in the middle of an existing entry, so
                    // split that entry.
                    splitStackEntry(newStartAddr, newEndAddr, entry, stackForClone);
                } else if (!(Long.compareUnsigned(newStartAddr, entryEndAddr) > 0
                        || Long.compareUnsigned(newEndAddr, entryStartAddr) < 0)) {
                    // There is overlap with top or bottom of an existing entry
                    // so shrink that entry.
                    shrinkStackEntry(newStartAddr, newEndAddr, entry, stackForClone);
                }
            }

            createAndAddStackEntry(newValueForStack.get(), newValueSize, 
                    newValueStartingAddress, stackForClone);
            stackForClone.sort(Comparator.comparing(StackEntry::getStartAddress));
        }
        
        int newRipVal = this.rip;
        if (incrementRIP) newRipVal++;

        this.mergeFlagsInto(flagsForClone);

        return new MachineState(registersForClone, stackForClone, this.tabList, 
                flagsForClone, newRipVal, this.callStackSize);
    }

    /**
     * Creates a new stack entry and adds it to the given stack.
     * 
     * @param newValue The value for the new stack entry.
     * @param newValueSize The size (in bytes) of the new stack entry.
     * @param address Starting address of the new stack entry.
     * @param stack The stack to which the new entry will be added. 
     */
    private void createAndAddStackEntry(BigInteger newValue, int newValueSize,
            long address, List<StackEntry> stack) {
        // Note: Java stores values in big endian format while x86 requires
        // little endian. We'll work with the big endian and switch over only
        // at the end.
        byte[] valArray = newValue.toByteArray();
        byte[] fullArrayBigEndian = new byte[newValueSize];
        int numToFill = newValueSize - valArray.length;
        byte toFill = 0;
        
        if (newValue.signum() == -1) {
            toFill = -1; // i.e. 0xFF
        }
        
        // Sign extend to fill total requested newValueSize
        for (int i = 0; i < numToFill; i++) {
            fullArrayBigEndian[i] = toFill;
        }
        
        // copy over the original value
        for (int dest = numToFill, src = 0; dest < newValueSize; dest++, src++) {
            fullArrayBigEndian[dest] = valArray[src];
        }
        
        // reverse the big endian version to get the little endian
        byte[] fullArrayLittleEndian = new byte[newValueSize];
        for (int src = 0, dest = newValueSize-1; src < newValueSize; src++, dest--) {
            fullArrayLittleEndian[dest] = fullArrayBigEndian[src];
        }
        
        StackEntry entry = new StackEntry(address, address + newValueSize - 1,
                fullArrayLittleEndian, rip);
        stack.add(entry);
    }

    /**
     * Shrink an existing stack entry based on overlap with a new entry.
     * This will result in the existing entry being replaced by a new, smaller
     * entry in the stack.
     * 
     * @param newEntryStartAddress Starting address of new stack entry.
     * @param newEntryEndAddress Ending address (inclusive) of new stack entry.
     * @param existingEntry The stack entry to shrink.
     * @param stack The stack.
     */
    private void shrinkStackEntry(long newEntryStartAddress, long newEntryEndAddress, 
            StackEntry existingEntry, List<StackEntry> stack) {
        long overlapStartAddr, overlapEndAddr;
        
        if (Long.compareUnsigned(newEntryStartAddress, existingEntry.getStartAddress()) < 0) {
            overlapStartAddr = existingEntry.getStartAddress();
        } else {
            overlapStartAddr = newEntryStartAddress;
        }
        
        if (Long.compareUnsigned(newEntryEndAddress, existingEntry.getEndAddress()) < 0) {
            overlapEndAddr = newEntryEndAddress;
        } else {
            overlapEndAddr = existingEntry.getEndAddress();
        }
        
        long shrunkenStartAddr, shrunkenEndAddr;
        if (Long.compareUnsigned(overlapStartAddr, existingEntry.getStartAddress()) > 0) {
            shrunkenStartAddr = existingEntry.getStartAddress();
        } else {
            shrunkenStartAddr = overlapEndAddr + 1;
        }
        
        if (Long.compareUnsigned(overlapEndAddr, existingEntry.getEndAddress()) < 0) {
            shrunkenEndAddr = existingEntry.getEndAddress();
        } else {
            shrunkenEndAddr = overlapStartAddr - 1;
        }
        
        int overlapRegionSize = (int) ((overlapEndAddr - overlapStartAddr) + 1);
        int copyRangeStart, copyRangeEnd;
        byte[] valOld = existingEntry.getValueArr();
        
        if (Long.compareUnsigned(existingEntry.getStartAddress(), overlapStartAddr) == 0) {
            copyRangeStart = overlapRegionSize;
            copyRangeEnd = valOld.length;
        } else {
            copyRangeStart = 0;
            copyRangeEnd = valOld.length - overlapRegionSize;
        }
        
        byte[] valNew = Arrays.copyOfRange(valOld, copyRangeStart, copyRangeEnd);
        StackEntry shrunkenEntry = new StackEntry(shrunkenStartAddr,
                shrunkenEndAddr,
                valNew,
                existingEntry.getOrigin());
        stack.add(shrunkenEntry);
        stack.remove(existingEntry);
    }

    /**
     * Splits an existing stack entry that completely encompasses a new entry.
     * This will result in two new stack entries: the part of the existing entry
     * that is above the new entry and the part that is below the new entry.
     * 
     * @param newEntryStartAddress Starting address of the new stack entry.
     * @param newEntryEndAddress Ending address (inclusive) of the new stack entry.
     * @param existingEntry The existing stack entry, which will be split.
     * @param stack The stack.
     */
    private void splitStackEntry(long newEntryStartAddress, long newEntryEndAddress, 
            StackEntry existingEntry, List<StackEntry> stack) {
        long bottomStartAddr = existingEntry.getStartAddress();
        long bottomEndAddr = newEntryStartAddress - 1;
        
        byte[] valOld = existingEntry.getValueArr();
        byte[] valBottom = Arrays.copyOfRange(valOld, 0,
                (int) ((bottomEndAddr - bottomStartAddr) + 1));
        
        long topStartAddr = newEntryEndAddress + 1;
        long topEndAddr = existingEntry.getEndAddress();
        byte[] valTop = Arrays.copyOfRange(valOld,
                ((int) ((newEntryEndAddress - newEntryStartAddress) + 1)) + valBottom.length,
                valOld.length);
        
        StackEntry sBottom = new StackEntry(bottomStartAddr,
                bottomEndAddr,
                valBottom,
                existingEntry.getOrigin());
        StackEntry sTop = new StackEntry(topStartAddr,
                topEndAddr,
                valTop,
                existingEntry.getOrigin());
        stack.add(sBottom);
        stack.add(sTop);
        stack.remove(existingEntry);
    }

    /**
     * Check if access to a given address is valid.
     * An invalid access includes anything outside of the range of valid stack 
     * addresses (i.e. 0x7F..FF down to %rsp).
     * 
     * @param startAddress The starting (i.e. lowest) address of the access.
     * @param size The size (number of bytes) of the memory access.
     * @return True if access is valid, false otherwise.
     */
    public boolean isValidMemoryAccess(long startAddress, int size) {
        long endAddress = (startAddress + size) - 1;
        long topOfStackAddress = this.getRegisterValue("rsp").longValue();
        
        /*
         * @note Since addresses are of long type, any address larger than
         * 0x7F..FF will be a negative number so we can simple check if the
         * address is a negative number.
         */
        return (startAddress >= topOfStackAddress && endAddress >= 0);
    }

    /**
     * Determines which parts of the full 8-byte register will be used by the
     * given register.
     *
     * @return Pair of the entryStartAddr (inclusive) and entryEndAddr (exclusive) indices for
 given register in its full register's byte array.
     */
    private static Pair<Integer, Integer> getByteRange(String regName) {
        String quadRegNames = "^r(ax|bx|cx|dx|si|di|bp|sp|8|9|10|11|12|13|14|15)$";
        String longRegNames = "^(e(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|10|11|12|13|14|15)d)$";
        String wordRegNames = "^((ax|bx|cx|dx|si|di|bp|sp)|r(8|9|10|11|12|13|14|15)w)$";
        String byteLowRegNames = "^((al|bl|cl|dl|sil|dil|bpl|spl)|r(8|9|10|11|12|13|14|15)b)$";
        String byteHighRegNames = "^(ah|bh|ch|dh)$";
        if (Pattern.matches(quadRegNames, regName)) {
            return new Pair<Integer, Integer>(0, 8);
        } else if (Pattern.matches(longRegNames, regName)) {
            return new Pair<Integer, Integer>(4, 8);
        } else if (Pattern.matches(wordRegNames, regName)) {
            return new Pair<Integer, Integer>(6, 8);
        } else if (Pattern.matches(byteLowRegNames, regName)) {
            return new Pair<Integer, Integer>(7, 8);
        } else if (Pattern.matches(byteHighRegNames, regName)) {
            return new Pair<Integer, Integer>(6, 7);
        } else {
            System.err.println("ERROR: Unknown register name: " + regName);
            System.exit(1);
            return null;
        }
    }

    /**
     * Determine the name of the 8-byte register used by the given register
     * name. For example: "eax", "ax", "ah", and "al" are all part of the "rax"
     * register.
     *
     * @param regName The name of the register to find.
     * @return Name of the 8-byte register that the given register was part of.
     */
    public static String getQuadName(String regName) {
        String quadRegNames = "^r(ax|bx|cx|dx|si|di|bp|sp|8|9|10|11|12|13|14|15)$";
        String longRegNames = "^(e(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|10|11|12|13|14|15)d)$";
        String wordRegNames = "^((ax|bx|cx|dx|si|di|bp|sp)|r(8|9|10|11|12|13|14|15)w)$";
        String byteLowRegNames = "^((al|bl|cl|dl|sil|dil|bpl|spl)|r(8|9|10|11|12|13|14|15)b)$";
        String byteHighRegNames = "^(ah|bh|ch|dh)$";

        if (Pattern.matches(quadRegNames, regName)) {
            return regName;
        } else if (Pattern.matches(longRegNames, regName)) {
            if (regName.charAt(0) == 'e') {
                return "r" + regName.substring(1);
            } else {
                return regName.substring(0, regName.length() - 1);
            }
        } else if (Pattern.matches(wordRegNames, regName)) {
            if (regName.charAt(0) != 'r') {
                return "r" + regName;
            } else // just strip off the "d" from the entryEndAddr
            {
                return regName.substring(0, regName.length() - 1);
            }
        } else if (Pattern.matches(byteLowRegNames, regName)) {
            if (regName.charAt(0) == 'r') {
                return regName.substring(0, regName.length() - 1);
            } else if (regName.length() == 2) {
                return "r" + regName.substring(0, regName.length() - 1) + "x";
            } else {
                return "r" + regName.substring(0, regName.length() - 1);
            }
        } else if (Pattern.matches(byteHighRegNames, regName)) {
            return "r" + regName.substring(0, regName.length() - 1) + "x";
        } else {
            System.err.println("ERROR: Unknown register name: " + regName);
            System.exit(1);
            return null;
        }
    }

    /**
     * Creates a new MachineState that is the same as the calling object but
     * with the rip register incremented by 1.
     *
     * @return A new MachineState that is identical to the calling object except
     * for the incremented rip register.
     */
    public MachineState cloneWithIncrementedRIP() {
        return new MachineState(this.registers, this.memory, this.tabList, this.statusFlags, rip + 1, this.callStackSize);
    }

    /**
     * Creates a new MachineState that is the same as the calling object but
     * with the rip register set to the given value.
     *
     * @param newRIPVal The value used by the rip register in the new state.
     * @return A new MachineState that is identical to the calling object except
     * for updated rip register.
     */
    public MachineState cloneWithNewRIP(int newRIPVal) {
        return new MachineState(this.registers, this.memory, this.tabList, this.statusFlags, newRIPVal, this.callStackSize);
    }

    public static byte[] getExtendedByteArray(BigInteger val, int origSize, int extendedSize, boolean zeroFill) {
        byte[] valArray = val.toByteArray();
        byte[] newVal = new byte[extendedSize];

        // TODO: make sure extendedSize isn't less than valArray's extendedSize
        // The value may be small enough that it doesn't need all of the
        // bytes available in newVal. We'll entryStartAddr the process of filling
        // in newVal by copying over what bytes we have at the appropriate
        // offset (since java is big endian).
        for (int src = 0, dest = (newVal.length - valArray.length);
                src < valArray.length; src++, dest++) {
            newVal[dest] = valArray[src];
        }

        // Fill in unused parts of newVal by sign extending.
        // Note that all bytes newVal are initialized to 0 so we only need
        // to make changes when this is a negative number.
        if (val.signum() == -1) {
            for (int i = 0; i < origSize - valArray.length; i++) {
                newVal[(extendedSize - origSize) + i] = (byte) 0xFF;
            }
        }

        if (!zeroFill && val.signum() == -1) {
            for (int i = 0; i < newVal.length - origSize; i++) {
                newVal[i] = (byte) 0xFF;
            }
        }

        return newVal;
    }

    /**
     * Create a new MachineState based on the current state but with an updated
     * value for a register.
     *
     * @param regName The register that will be updated.
     * @param val The new value of the given register.
     * @param flags The condition flags to modify for the new state.
     * @param incrementRIP Whether to increment the RIP or not.
     * @return A new state that is the same as the current but with new binding
     * from given register to given val
     */
    public MachineState cloneWithUpdatedRegister(String regName,
            Optional<BigInteger> val,
            Map<String, Boolean> flags,
            boolean incrementRIP) throws x86RuntimeException {
        Map<String, RegisterState> reg = this.registers;
        List<StackEntry> mem = this.memory;
        if (val.isPresent()) {
            // Enforce proper alignment of rsp (i.e. multiple of 8)
            if (regName.equals("rsp") && val.get().longValue() % 8 != 0) {
                throw new x86RuntimeException("rsp should be multiple of 8");
            }
            
            /* 
             * If we are incrementing rsp, that means we are reducing the size
             * of the stack. As a result, we may need to remove some entries
             * from the stack.
             */
            if (regName.equals("rsp")
                    && val.get().compareTo(getRegisterValue("rsp")) == 1) {

                /* 
                 * We've reduced the size of the stack, so look for entries to
                 * remove. Any entry with a starting address less than the new
                 * value for rsp (i.e. above the updated stack) will be removed.
                 */
                List<StackEntry> toRemove = new ArrayList<>();
                for (StackEntry se : this.memory) {
                    long seStartAddr = se.getStartAddress();
                    
                    if (seStartAddr < val.get().longValue()) {
                        // need to remove this entry... eventually
                        toRemove.add(se);
                    }
                }

                // If we found entries to remove, we'll clone the memory and
                // make changes to that clone.
                if (!toRemove.isEmpty()) {
                    mem = new ArrayList<>(this.memory);
                    mem.removeAll(toRemove);
                }
            }

            // The register file contains only the quad sized registers (e.g.
            // rax). All other register updates need to be translated to one of
            // these quad register names and given an appropriate part of the
            // register to update.
            String quadName = getQuadName(regName);
            Pair<Integer, Integer> range = getByteRange(regName);
            int startIndex = range.getKey();
            int endIndex = range.getValue();

            reg = new HashMap<>(this.registers);

            byte[] newVal = getExtendedByteArray(val.get(), (endIndex - startIndex), (endIndex - startIndex), false);

            byte[] newValQuad = Arrays.copyOf(this.registers.get(quadName).getValue(), 8);
            for (int src = 0, dest = startIndex; dest < endIndex; src++, dest++) {
                newValQuad[dest] = newVal[src];
            }

            // Long word registers (e.g. eax) are special in that we zero extend
            // them to fill the whole quad word. Other register sizes don't get
            // extended (e.g. al only modifies the least significant byte).
            if (startIndex == 4 && endIndex == 8) {
                for (int i = 0; i < 4; i++) {
                    newValQuad[i] = 0;
                }
            }

            reg.put(quadName, new RegisterState(newValQuad, rip));
        }
        int newRipVal = rip;

        if (incrementRIP) {
            newRipVal++;
        }

        mergeFlagsInto(flags);

        return new MachineState(reg, mem, this.tabList, flags, newRipVal, this.callStackSize);
    }

    /**
     * Merges the calling object's flags into the given flags, copying over a
     * flag only when it isn't set in the given flags.
     *
     * @param flags The flags to merge into.
     */
    private void mergeFlagsInto(Map<String, Boolean> flags) {
        if (!flags.containsKey("zf")) {
            flags.put("zf", this.statusFlags.get("zf"));
        }
        if (!flags.containsKey("sf")) {
            flags.put("sf", this.statusFlags.get("sf"));
        }
        if (!flags.containsKey("of")) {
            flags.put("of", this.statusFlags.get("of"));
        }
        if (!flags.containsKey("cf")) {
            flags.put("cf", this.statusFlags.get("cf"));
        }
    }

    /**
     * @return The BigInteger representation of the value in the rip register.
     */
    public int getRipRegister() {
        return rip;
    }

    /**
     * Gets the value stored in the given register.
     */
    public BigInteger getRegisterValue(String regName) {
        byte[] ba = null;

        String quadName = getQuadName(regName);
        Pair<Integer, Integer> range = getByteRange(regName);
        int startIndex = range.getKey();
        int endIndex = range.getValue();

        ba = registers.get(quadName).getValue();
        return new BigInteger(Arrays.copyOfRange(ba, startIndex, endIndex));
    }

    /**
     * Gets the value stored in the given register.
     */
    public BigInteger getCombinedRegisterValue(OpSize size) {
        String upperRegName = null;
        String lowerRegName = null;

        switch (size) {
            case QUAD:
                upperRegName = "rdx";
                lowerRegName = "rax";
                break;
            case LONG:
                upperRegName = "edx";
                lowerRegName = "eax";
                break;
            case WORD:
                upperRegName = "dx";
                lowerRegName = "ax";
                break;
            case BYTE:
                return getRegisterValue("ax");
            default:
                throw new RuntimeException("Unsupported op size");
        }

        Pair<Integer, Integer> range = getByteRange(upperRegName);
        int startIndex = range.getKey();
        int endIndex = range.getValue();

        byte[] upper = Arrays.copyOfRange(registers.get("rdx").getValue(), startIndex, endIndex);
        byte[] lower = Arrays.copyOfRange(registers.get("rax").getValue(), startIndex, endIndex);

        byte[] combined = new byte[2 * size.numBytes()];

        for (int i = 0; i < size.numBytes(); i++) {
            combined[i] = upper[i];
            combined[i + size.numBytes()] = lower[i];
        }

        return new BigInteger(combined);
    }

    /**
     * Gets the value stored at the given memory address.
     *
     * @param address The starting address where the value is stored.
     * @param size The number of bytes of memory to read.
     * @return The value at the given address with the given size
     * @throws edu.sandiego.bcl.x86RuntimeException
     */
    public BigInteger getMemoryValue(long address, int size) throws x86RuntimeException {
        if (!this.isValidMemoryAccess(address, size)) {
            throw new x86RuntimeException("Illegal read from 0x" 
                + String.format("%X", address));
        }
        
        // Enforce Intel-recommended alignments.
        if (address % size != 0) {
            throw new x86RuntimeException("Unaligned memory access");
        }
        
        byte[] valArray = new byte[size];
        int bytes_remaining = size;
        
        int i;
        long endAddrOfPrevEntry = -1;
        
        // Find which stack entry this address starts in and copy over bytes the
        // bytes from that entry to valArray.
        for (i = 0; i < this.memory.size(); i++) {
            StackEntry se = this.memory.get(i);
            if (se.getStartAddress() <= address && se.getEndAddress() >= address) {
                // note where we left off (pick up here later)
                endAddrOfPrevEntry = se.getEndAddress();
                
                // copy over the appropriate part of the stack entry
                byte[] stackEntryValArray = se.getValueArr();
                int offset = (int)(address - se.getStartAddress());
                int length = Math.min((int)((se.getEndAddress() - address) + 1), 
                                        bytes_remaining);
                
                System.arraycopy(stackEntryValArray, offset, valArray, 0, length);
                
                bytes_remaining -= length;
                break;
            }
        }
        
        // If we couldn't find an entry with address in it, then we tried to
        // read from an uninitialized address so throw an exception.
        if (i == this.memory.size()) {
            throw new x86RuntimeException("Read from uninitialized memory: 0x" 
                    + String.format("%X", address).replaceFirst("F{4,}","F..F"));
        }

        i++; // We broke the last loop so increment never happened.
        while (i < this.memory.size() && bytes_remaining > 0) {
            StackEntry se = this.memory.get(i);
            // if this entry doesn't pick up right where the last one left off,
            // then we are trying to read from uninitialized memory.
            if (se.getStartAddress() != (endAddrOfPrevEntry+1)) {
                throw new x86RuntimeException("Read from uninitialized memory: 0x" 
                    + String.format("%X", address + (size-bytes_remaining)).replaceFirst("F{4,}","F..F"));
            }
            
             // note where we left off (pick up here later)
            endAddrOfPrevEntry = se.getEndAddress();

            // copy over the appropriate part of the stack entry
            byte[] stackEntryValArray = se.getValueArr();
            int length = Math.min((int)((se.getEndAddress() - se.getStartAddress()) + 1), 
                                    bytes_remaining);

            // Since this won't be the first stack entry we are reading from,
            // it follows that we will start copying from the beginning.
            System.arraycopy(stackEntryValArray, 0, 
                    valArray, (size-bytes_remaining),
                    length);

            bytes_remaining -= length;
        }
        
        // If we got here and there are still bytes to be read, that means we
        // have reached an unininitialized area of memory.
        if (bytes_remaining > 0) {
            throw new x86RuntimeException("Read from uninitialized memory: 0x" 
                    + String.format("%X", address + (size-bytes_remaining)).replaceFirst("F{4,}","F..F"));
        }
        
        // Array is in little endian but need to make it big endian for Java so
        // we'll reverse the array here.
        for (int j = 0; j < size / 2; j++) {
            byte tmp = valArray[j];
            valArray[j] = valArray[size - 1 - j];
            valArray[size - 1 - j] = tmp;
        }
        
        return new BigInteger(valArray);
    }

    /**
     * Returns a list of all registers.
     *
     * @param regHistory Ordered list containing a history of register usage.
     * @return List of Register objects for all of the registers in this state.
     */
    public List<Register> getRegisters(List<String> regHistory) {
        ArrayList<Register> arr = new ArrayList<>();
        for (Map.Entry<String, RegisterState> entry : registers.entrySet()) {
            BigInteger b = new BigInteger(entry.getValue().getValue());
            byte[] ba = b.toByteArray();
            String fullS = "";
            for (int i = 0; i < 8 - ba.length; i++) {
                if (b.signum() == -1) {
                    fullS += "FF";
                } else {
                    fullS += "00";
                }
            }

            for (byte i : ba) {
                fullS += String.format("%02X", i);
            }
            int regHist = regHistory.lastIndexOf(entry.getKey());
            arr.add(new Register(entry.getKey(), regHist, entry.getValue().getOrigin(), fullS));
        }
        return arr;
    }

    /**
     * Returns a list of stack entries.
     */
    public List<StackEntry> getStackEntries() {
        return memory;
    }

    /**
     * Returns a list of tabs.
     */
    public List<Tab> getTabs() {
        return tabList;
    }

    public String toString() {
        String s = "Registers:\n";
        for (Map.Entry<String, RegisterState> entry : registers.entrySet()) {
            BigInteger b = new BigInteger(entry.getValue().getValue());
            byte[] ba = b.toByteArray();
            s += "\t" + entry.getKey() + ": " + b.toString() + " (0x";
            for (byte i : ba) {
                s += String.format("%02X", i);
            }
            s += ")\n";
        }

        s += "Status Flags:\n";
        for (Map.Entry<String, Boolean> entry : statusFlags.entrySet()) {
            s += "\t" + entry.getKey() + ": " + (entry.getValue() ? "1" : "0") + "\n";
        }

        s += "Memory:\n";
        for (StackEntry e : this.memory) {
            byte[] ba = e.getValueArr();
            s += "\t" + Long.toHexString(e.getStartAddress()) + ": ";
            for (byte b : ba) {
                s += String.format("%02X", b);
            }
            s += "\n";
        }
        return s;
    }
    
    public void setRip(int i){
        if(i >= 0){
            this.rip = i;
        }
    }
    
    public void pushToCallStack(){
        callStackSize++;
    }
    
    public void popFromCallStack(){
        callStackSize--;
    }
    
    public int getCallStackSize(){
        return this.callStackSize;
    }
}
