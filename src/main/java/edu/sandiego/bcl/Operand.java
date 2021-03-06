package edu.sandiego.bcl;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An abstract class representing an x86 operand.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
public abstract class Operand {
    
   /**
     * The size of the operand.
     */
    protected OpSize opSize;
    
    public Operand(OpSize size) {
        this.opSize = size;
    }
    
    public OpSize getOpSize() { return this.opSize; }


    /**
     * @param state The state of the machine.
     * @return The value of the operand in a machine with the given state.
     */
    public abstract BigInteger getValue(MachineState state) throws x86RuntimeException;

    /**
     * @param currState The current state of the machine.
     * @param val The value to update the operand with.
     * @param flags The condition flags to be set in the new state.
     * @param updateRIP Flag indicating whether we should increment the rip register
     * @return The state after updating the current state with the new value for
     * the operand.
     */
    public abstract MachineState updateState(MachineState currState, 
            Optional<BigInteger> val, Map<String, Boolean> flags, 
            boolean updateRIP) throws x86RuntimeException;

    /**
     * Returns the names of the registers used by this operand.
     *
     * @return Set containing names of registers used by this operand.
     */
    public abstract Set<String> getUsedRegisters();
    
    /**
     * Updates the labels in this operand with the given name to refer to the
     * given label.
     *
     * @param labelName The name of the label to update.
     * @param label The new value for the label.
     */
    public void updateLabels(String labelName, x86Label label) {}
    
    /**
     * Constructs a string that provides a description of this operand.
     * 
     * @return A string with a description of this operand.
     */
    public abstract String getDescriptionString();
    
    /**
     * Changes inferred size to an explicit size.
     * This does nothing if this operand is not of inferred size.
     * 
     * @param explicitSize The new, explicit size for this operand.
     * @return True if this operand had an inferred type and we could change it
     * to the given type, False otherwise.
     */
    public boolean makeSizeExplicit(OpSize explicitSize) {
        return false;
    }
}
