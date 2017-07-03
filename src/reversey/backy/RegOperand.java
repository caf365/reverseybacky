package reversey.backy;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * A class representing an x86-64 register operand (e.g. %eax).
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
public class RegOperand extends Operand {

    /**
     * The name of the register, sans % (e.g. "eax")
     */
    private String regName;

    /**
     * The size of the operand.
     */
    private OpSize opSize;

    public RegOperand(String regName, OpSize opSize) {
        this.regName = regName;
        this.opSize = opSize;
    }

    @Override
    public BigInteger getValue(MachineState state) {
        return state.getRegisterValue(regName);
    }

    @Override
    public MachineState updateState(MachineState currState, Optional<BigInteger> val, Map<String, Boolean> flags, boolean updateRIP) {
        return currState.getNewState(this.regName, val, flags, updateRIP);
    }

    @Override
    public Set<String> getUsedRegisters(){ 
        HashSet<String> s = new HashSet<String>();
        s.add(MachineState.getQuadName(regName));
        return s;
    }
    
    @Override
    public String toString() {
        return "%" + regName;
    }
}

