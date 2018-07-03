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
     * Create a new state with all registers (except %rsp) initialized to 0 but
     * no memory initialization. %rsp is initialized to 0x7FFFFFFF.
     */
    public MachineState() {
        this.registers = new HashMap<String, RegisterState>();
        this.memory = new ArrayList<StackEntry>();
        this.tabList = new ArrayList<Tab>();
        this.statusFlags = new HashMap<String, Boolean>();
        this.rip = 0;

        String[] regNames = {"rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"};
        for (String s : regNames) {
            registers.put(s, new RegisterState(new byte[8], -1));
        }

        long initRSP = 1 << 30;
        initRSP <<= 30;
        initRSP <<= 3;
        initRSP = ~initRSP;
        registers.put("rsp", new RegisterState(ByteBuffer.allocate(8).putLong(initRSP).array(), -1)); // rsp = 0x7FFFFFFFFFFFFFFF

        String[] flagNames = {"zf", "sf", "of", "cf"};
        for (String s : flagNames) {
            statusFlags.put(s, false);
        }
    }

    public MachineState(Map<String, RegisterState> reg, List<StackEntry> mem, List<Tab> tList, Map<String, Boolean> flags, int RIP) {
        this.registers = reg;
        this.memory = mem;
        this.tabList = tList;
        this.statusFlags = flags;
        this.rip = RIP;
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
     * @param address The starting (i.e. lowest) address that will be changed.
     * @param val The new value of the given memory address.
     * @param size The number of bytes to write to memory.
     * @param flags The condition flags to modify for the new state.
     * @param incrementRIP Whether to increment RIP or not.
     * @return A new state that is the same as the current but with new binding
     * from given address to given val.
     */
    public MachineState cloneWithUpdatedMemory(long address,
            Optional<BigInteger> val,
            int size, Map<String, Boolean> flags,
            boolean incrementRIP) {
        
        List<StackEntry> mem = this.memory;
        Map<String, RegisterState> reg = this.registers;

        // TODO: Comment
        if (val.isPresent()) {
            long newStartAddr = address;
            long newEndAddr = address + size - 1;
            
            // FIXME: make sure there wasn't overflow causing newEndAddr
            // to be less than newStartAddr
            
            mem = new ArrayList<>(this.memory);
            
            for (StackEntry entry : this.memory) {
                long entryStartAddr = entry.getStartAddress();
                long entryEndAddr = entry.getEndAddress();
                
                if (Long.compareUnsigned(newStartAddr, entryStartAddr) <= 0 
                        && Long.compareUnsigned(newEndAddr, entryEndAddr) >= 0) {
                    // The new StackEntry completely ensconces the old, so we'll
                    // simply remove the old one
                    mem.remove(entry);
                } else if (Long.compareUnsigned(newStartAddr, entryStartAddr) > 0 
                        && Long.compareUnsigned(newEndAddr, entryEndAddr) < 0) {
                    // The new stack entry is contained completed within the old
                    // entry so we'll split the old entry into two entries, one
                    // for the bottom part and one for the top part
                    long bottomStartAddr = entryStartAddr;
                    long bottomEndAddr = newStartAddr - 1;
                    
                    byte[] valOld = entry.getValueArr();
                    byte[] valBottom = Arrays.copyOfRange(valOld, 0, 
                            (int) ((bottomEndAddr - bottomStartAddr) + 1));
                    
                    long topStartAddr = newEndAddr + 1;
                    long topEndAddr = entryEndAddr;
                    byte[] valTop = Arrays.copyOfRange(valOld, 
                            ((int) ((newEndAddr - newStartAddr) + 1)) + valBottom.length, 
                            valOld.length);

                    StackEntry sBottom = new StackEntry(bottomStartAddr, 
                                                        bottomEndAddr, 
                                                        valBottom, 
                                                        entry.getOrigin());
                    StackEntry sTop = new StackEntry(topStartAddr, 
                                                        topEndAddr, 
                                                        valTop, 
                                                        entry.getOrigin());
                    mem.add(sBottom);
                    mem.add(sTop);
                    mem.remove(entry);
                } else if (!(Long.compareUnsigned(newStartAddr, entryEndAddr) > 0
                        || Long.compareUnsigned(newEndAddr, entryStartAddr) < 0)) {
                    // The old entry either overlaps at the bottom or top of the
                    // old entry, so we'll shrink the old entry.
                    long overlapStartAddr, overlapEndAddr;
                    
                    if (Long.compareUnsigned(newStartAddr, entryStartAddr) < 0) {
                        overlapStartAddr = entryStartAddr;
                    } else {
                        overlapStartAddr = newStartAddr;
                    }

                    if (Long.compareUnsigned(newEndAddr, entryEndAddr) < 0) {
                        overlapEndAddr = newEndAddr;
                    } else {
                        overlapEndAddr = entryEndAddr;
                    }

                    long shrunkenStartAddr, shrunkenEndAddr;
                    if (Long.compareUnsigned(overlapStartAddr, entryStartAddr) > 0) {
                        shrunkenStartAddr = entryStartAddr;
                    } else {
                        shrunkenStartAddr = overlapEndAddr + 1;
                    }

                    if (Long.compareUnsigned(overlapEndAddr, entryEndAddr) < 0) {
                        shrunkenEndAddr = entryEndAddr;
                    } else {
                        shrunkenEndAddr = overlapStartAddr - 1;
                    }
                    
                    int overlapRegionSize = (int) ((overlapEndAddr - overlapStartAddr) + 1);
                    
                    // Determine the range of indices to keep (by copying over)
                    int copyRangeStart, copyRangeEnd;
                    byte[] valOld = entry.getValueArr();
                    if (Long.compareUnsigned(entryStartAddr, overlapStartAddr) == 0) {
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
                                                                entry.getOrigin());
                    mem.add(shrunkenEntry);
                    mem.remove(entry);
                }
            }

            // Note: Java stores values in big endian format while x86 requires
            // little endian. We'll work with the big endian and switch over only
            // at the end.
            byte[] valArray = val.get().toByteArray();
            byte[] fullArrayBigEndian = new byte[size];
            int numToFill = size - valArray.length;
            byte toFill = 0;

            if (val.get().signum() == -1) {
                toFill = -1; // i.e. 0xFF
            }

            // Sign extend to fill total requested size
            for (int i = 0; i < numToFill; i++) {
                fullArrayBigEndian[i] = toFill;
            }

            // copy over the original value
            for (int dest = numToFill, src = 0; dest < size; dest++, src++) {
                fullArrayBigEndian[dest] = valArray[src];
            }
            
            // reverse the big endian version to get the little endian
            byte[] fullArrayLittleEndian = new byte[size];
            for (int src = 0, dest = size-1; src < size; src++, dest--) {
                fullArrayLittleEndian[dest] = fullArrayBigEndian[src];
            }

            StackEntry entry = new StackEntry(address, address + size - 1, 
                                                fullArrayLittleEndian, rip);
            mem.add(entry);
        }
        
        int newRipVal = this.rip;
        if (incrementRIP) newRipVal++;

        mergeFlags(flags);

        return new MachineState(reg, mem, this.tabList, flags, newRipVal);
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
        return new MachineState(this.registers, this.memory, this.tabList, this.statusFlags, rip + 1);
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
        return new MachineState(this.registers, this.memory, this.tabList, this.statusFlags, newRIPVal);
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
            boolean incrementRIP) {
        Map<String, RegisterState> reg = this.registers;
        List<StackEntry> mem = this.memory;
        if (val.isPresent()) {
            /* 
             * If we are incrementing rsp, that means we are reducing the size
             * of the stack. As a result, we may need to remove some entries
             * from the stack.
             */
            if (regName.equals("rsp")
                    && val.get().compareTo(getRegisterValue("rsp")) == 1) {

                // We've reduced the size of the stack, so look for entries to
                // remove.
                List<StackEntry> toRemove = new ArrayList<>();
                for (StackEntry se : this.memory) {
                    long seStartAddr = se.getStartAddress();
                    if (seStartAddr >= getRegisterValue("rsp").longValue()
                            && seStartAddr < val.get().longValue()) {
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

        mergeFlags(flags);

        return new MachineState(reg, mem, this.tabList, flags, newRipVal);
    }

    /**
     * Merges the calling object's flags into the given flags, copying over a
     * flag only when it isn't set in the given flags.
     *
     * @param flags The flags to merge into.
     */
    private void mergeFlags(Map<String, Boolean> flags) {
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
     */
    public BigInteger getMemoryValue(long address, int size) {
        //TODO: Allow addresses that aren't starting addresses but are still valid 
        for (StackEntry e : this.memory) {
            if (e.getStartAddress() == address) {
                return e.getValAsBigInt();
            }
        }
        
        // Couldn't find the address on the stack so throw an exception to
        // be caught by the simulator.
        throw new RuntimeException("Read from illegal address: " 
                + String.format("%X", address));
    }

    /**
     * Returns a list of all registers.
     *
     * @param regHistory Ordered list containing a history of register usage.
     * @return List of Register objects for all of the registers in this state.
     */
    public List<Register> getRegisters(List<String> regHistory) {
        ArrayList<Register> arr = new ArrayList<Register>();
        for (Map.Entry<String, RegisterState> entry : registers.entrySet()) {
            BigInteger b = new BigInteger(entry.getValue().getValue());
            byte[] ba = b.toByteArray();
            String s = "0x";
            String fullS = "";
            for (int i = 0; i < 8 - ba.length; i++) {
                if (b.signum() == -1) {
                    fullS += "FF";
                } else {
                    fullS += "00";
                }
            }
            if (ba.length != 8) {
                if (b.signum() == -1) {
                    s += "F...";
                } else {
                    s += "0...";
                }
            }
            for (byte i : ba) {
                s += String.format("%02X", i);
                fullS += String.format("%02X", i);
            }
            int regHist = regHistory.lastIndexOf(entry.getKey());
            arr.add(new Register(entry.getKey(), s, regHist, entry.getValue().getOrigin(), fullS));
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
}