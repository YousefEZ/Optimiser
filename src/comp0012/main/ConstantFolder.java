package comp0012.main;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

/** @noinspection WeakerAccess */
public class ConstantFolder {

    /** @noinspection WeakerAccess */ ClassParser parser = null;
    /** @noinspection WeakerAccess */ ClassGen gen = null;

    /** @noinspection WeakerAccess */ JavaClass original = null;
    /** @noinspection WeakerAccess */ JavaClass optimized = null;

    private ClassGen cgen;
    private ConstantPoolGen cpgen;
    private Stack<Number> valuesStack;
    private Stack<InstructionHandle> loadInstructions;
    private HashMap<Integer, Number> variables;
    private List<InstructionHandle> loopBounds;

    // these are used for PeepHole Optimization (Detecting dead code).
    private HashMap<Integer, InstructionHandle[]> variableInstructions;
    private HashMap<Integer, Boolean> variableUsed;

    private boolean deleteElseBranch;
    private boolean blockOperationIfInLoop;

    private static final boolean LOG = true; // switch this to false if you don't want logging.

    public ConstantFolder(String classFilePath) {
        try {
            this.parser = new ClassParser(classFilePath);
            this.original = this.parser.parse();
            this.gen = new ClassGen(this.original);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void displayLog(String log){
        if (LOG) System.out.println(log);
    }

    // <------------------------------------------------ Optimisation ------------------------------------------------->

    public void initialise(){
        cgen = new ClassGen(original);
        cgen.setMajor(50);
        cgen.setMinor(0);
        cpgen = cgen.getConstantPool();

        valuesStack = new Stack<Number>();
        variables = new HashMap<Integer, Number>();
        loadInstructions = new Stack<InstructionHandle>();

        variableInstructions = new HashMap<Integer, InstructionHandle[]>();
        variableUsed = new HashMap<Integer, Boolean>();
        displayLog("[INIT] Initialization Step Complete.");
        displayLog("**********[READY] Ready To Optimize Class: " + cgen.getClassName() + "**********");
    }

    /** @noinspection WeakerAccess */
    public void optimize() {
        initialise();

        // Implement your optimization here
        runOptimization();
        this.optimized = cgen.getJavaClass();
    }

    private void runOptimization(){
        int numberOfMethods = cgen.getMethods().length;
        for (int methodPosition = 0; methodPosition < numberOfMethods; methodPosition++ ) {
            displayLog("-------------- [RUN_OPTIMIZE] Starting Optimization On: " + cgen.getMethodAt(methodPosition).getName() + " --------------");
            runRegularOptimization(methodPosition);
            runPeepHoleOptimization(methodPosition);
        }
    }

    private void runRegularOptimization(int methodPosition){
        displayLog("============= [OPTIMIZE] Starting Regular Optimization =============");
        regularOptimization(cgen.getMethodAt(methodPosition)); // optimizes each method.
        clearDataContainers();
        displayLog("\n");
    }

    private void runPeepHoleOptimization(int methodPosition){
        displayLog("============= [OPTIMIZE] Starting PeepHole Optimization =============");
        boolean optimized = false;
        while (!optimized){
            // keeps doing peephole optimization until there are no more changes.
            optimized = peepHoleOptimization(cgen.getMethodAt(methodPosition));
            clearDataContainers();
        }
        displayLog("\n");
    }

    // replaces the original method code with the optimized method code.
    private void replaceMethodCode(Method originalMethod, MethodGen methodGen){
        methodGen.setMaxStack();
        methodGen.setMaxLocals();
        Method newMethod = methodGen.getMethod();
        cgen.replaceMethod(originalMethod, newMethod);
    }

    // clears all the data in all the containers.
    private void clearDataContainers() {
        deleteElseBranch = false;
        blockOperationIfInLoop = false;
        loadInstructions.clear();
        valuesStack.clear(); // clears stack for next method.
        variables.clear(); // clears variables for next method.
        variableInstructions.clear();
        variableUsed.clear();
        displayLog("[CLEAR_DATA] Cleared All Data On Stack");
    }

    // <============================================ Regular Optimization =============================================>

    private void regularOptimization(Method method) {
        displayLog("[REGULAR_OPTIMIZATION] Starting Regular Optimization");
        Code methodCode = method.getCode(); // gets the code inside the method.
        InstructionList instructionList = new InstructionList(methodCode.getCode()); // gets code and makes an list of Instructions.
        MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(),
                null, method.getName(), cgen.getClassName(), instructionList, cpgen);
        displayLog("*[REGULAR_OPTIMIZATION] Display Initial Instruction List");
        displayLog(instructionList.toString());

        loadLoopBounds(instructionList);
        for (InstructionHandle handle : instructionList.getInstructionHandles()) {
            // Main Optimization (SimpleFolding, ConstantVariableFolding, DynamicVariableFolding).
            handleInstruction(handle, instructionList);
        }

        displayLog("*[REGULAR_OPTIMIZATION] Optimized Code: ");
        displayLog(instructionList.toString());
        instructionList.setPositions(true);
        replaceMethodCode(method, methodGen);
    }

    /** handles the instruction inside of the InstructionHandle by first checking its type then optimising it.
     *
     * @param handle wrapper that contains the instruction.
     * @param instructionList list of all the instruction, this is required because some changes are made here.
     */
    private void handleInstruction(InstructionHandle handle, InstructionList instructionList){
        Instruction instruction = handle.getInstruction(); // gets the instruction from the instruction handle.
        displayLog("[INSTRUCTION] Next Instruction -> " + instruction);

        // Operation Instructions (Instructions that use the previous 2 loaded values)
        if (instruction instanceof ArithmeticInstruction) handleArithmetic(handle, instructionList);
        if (instruction instanceof LCMP) handleLongComparison(handle, instructionList);
        else if (instruction instanceof IfInstruction) handleComparison(handle, instructionList);

        // Instructions that use the previous loaded value.
        if (instruction instanceof ConversionInstruction) handleConversion(handle, instructionList);
        if (instruction instanceof GotoInstruction) handleGoTo(handle, instructionList);

        if (instruction instanceof StoreInstruction) handleStore(handle);

        // Load Instructions [Load Constant (SimpleFolding) / Load Variable (ConstantVariableFolding)]
        if (isLoadConstantValueInstruction(instruction)) handleLoad(handle);
        else if (instruction instanceof LoadInstruction) handleVariableLoad(handle);
        else blockOperationIfInLoop = false; // if it is not a load instruction then switch off block after handling.

        displayLog("");
    }

    //                     <==================== Handling Instructions ====================>

    // Method that converts the value on the top of the stack to another type.
    private void handleConversion(InstructionHandle handle, InstructionList instructionList) {
        if (isLoadConstantValueInstruction(loadInstructions.peek().getInstruction()) || !blockOperationIfInLoop) {
            // if its a constant or if the variable does not change in the loop.
            valuesStack.push(convertValue(handle.getInstruction(), valuesStack.pop()));
            displayLog("[CONVERSION] Converted Top Of Stack Value To: " + valuesStack.peek());

            removeHandle(instructionList, loadInstructions.pop()); // remove load instruction
            handle.setInstruction(createLoadInstruction(valuesStack.peek(), cpgen)); // change conversion instruction with load.
            loadInstructions.push(handle); // push new load instruction onto the loadInstruction stack.
        }
    }

    // Method that checks whether to delete the Else Branch of a IfInstruction, and deletes it if necessary.
    private void handleGoTo(InstructionHandle handle, InstructionList instructionList) {
        if (deleteElseBranch){
            deleteElseBranch = false;
            GotoInstruction instruction = (GotoInstruction) handle.getInstruction();
            InstructionHandle targetHandle = instruction.getTarget();
            removeHandle(instructionList, handle, targetHandle.getPrev());
        }
    }

    private void handleLongComparison(InstructionHandle handle, InstructionList instructionList) {
        if (blockOperationIfInLoop) return;

        long first = (Long) valuesStack.pop();
        long second = (Long) valuesStack.pop();

        // LCMP returns -1, 0, 1.
        int result = 0;
        if (first > second) result = 1;
        else if (first < second) result = -1;

        removePreviousTwoLoadInstructions(instructionList);
        handle.setInstruction(createLoadInstruction(result, cpgen));
        loadInstructions.push(handle);
        valuesStack.push(result);
    }

    private void handleComparison(InstructionHandle handle, InstructionList instructionList) {
        if (blockOperationIfInLoop) return;

        IfInstruction comparisonInstruction = (IfInstruction) handle.getInstruction();

        if (getComparisonOutcome(instructionList, comparisonInstruction)) {
            removeHandle(instructionList, handle);
            deleteElseBranch = true;
        } else {
            // if outcome is false then remove the comparison, and remove the if branch (all instructions to target).
            InstructionHandle targetHandle = comparisonInstruction.getTarget();
            removeHandle(instructionList, handle, targetHandle.getPrev());
        }
    }

    private void handleStore(InstructionHandle handle) {
        Number value = valuesStack.pop();
        loadInstructions.pop();
        displayLog("[STORE] Storing Value: " + value);
        int key = ((StoreInstruction) handle.getInstruction()).getIndex();
        variables.put(key, value);
    }

    private void handleVariableLoad(InstructionHandle handle) {
        int variableKey = ((LoadInstruction) handle.getInstruction()).getIndex();
        valuesStack.push(variables.get(variableKey));
        loadInstructions.push(handle);
        displayLog("[LOAD_VARIABLE] Loaded Variable Value: " + valuesStack.peek());
        // if not already blocking: block if this variable load is in a loop & the variable stores a value in the loop.
        blockOperationIfInLoop = blockOperationIfInLoop || variableChangesInLoop(handle, variableKey);
        displayLog("[BLOCK] Status: " + blockOperationIfInLoop);
    }

    private void handleLoad(InstructionHandle handle) {
        valuesStack.push(getLoadConstantValue(handle.getInstruction(), cpgen));
        loadInstructions.push(handle);
        displayLog("[LOAD_CONSTANT] Loaded Constant Value: " + valuesStack.peek());
    }

    private void handleArithmetic(InstructionHandle handle, InstructionList instructionList) {
        if (blockOperationIfInLoop) return; // if block operation is true, then skip this instruction.

        Number second = valuesStack.pop(); // last load is on the top of the stack.
        Number first = valuesStack.pop();
        valuesStack.push(performArithmeticOperation(first, second, handle.getInstruction()));

        displayLog("[ARITHMETIC_OPERATION] Calculated Value: " + valuesStack.peek() + " Pushed Onto Stack.");
        condenseOperationInstructions(instructionList, handle, valuesStack.peek()); // using peek because it needs to be in stack.
    }

    // <=========================================== PeepHole Optimization ============================================>

    private boolean peepHoleOptimization(Method method){
        InstructionList instructionList = new InstructionList(method.getCode().getCode()); // gets code and makes an list of Instructions.
        MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(),
                null, method.getName(), cgen.getClassName(), instructionList, cpgen);

        loadLoopBounds(instructionList);
        for (InstructionHandle handle : instructionList.getInstructionHandles()) {
            checkInstruction(handle);
        }
        boolean optimized = removeDeadCode(instructionList);

        instructionList.setPositions(true);
        replaceMethodCode(method, methodGen);
        return optimized;
    }

    // deletes dead code, i.e. variables that are not used.
    private boolean removeDeadCode(InstructionList instructionList){
        displayLog("[DEAD_CODE_REMOVE] Removing The Following Instructions: ");
        boolean optimized = true;
        for (int key: variableUsed.keySet()){
            if (!variableUsed.get(key)){
                optimized = false;
                removeHandle(instructionList, variableInstructions.get(key)[0]); // delete the LOAD instruction.
                removeHandle(instructionList, variableInstructions.get(key)[1]); // delete the STORE instruction.
            }
        }
        return optimized;
    }

    //                       <===================== Instruction Recorders =====================>

    // handles the instruction inside of the InstructionHandle by first checking its type then optimising it.
    private void checkInstruction(InstructionHandle handle){
        Instruction instruction = handle.getInstruction(); // gets the instruction from the instruction handle.

        // Load Instructions
        if (isLoadConstantValueInstruction(instruction)) checkLoad(handle);
        else if (instruction instanceof LoadInstruction) checkVariableLoad(handle);

        // Store Instructions
        if (instruction instanceof StoreInstruction) checkStore(handle);
    }

    // Method that check if a variable value is used which implies that the variable is not dead.
    private void checkVariableLoad(InstructionHandle handle) {
        int key = ((LoadInstruction) handle.getInstruction()).getIndex();
        loadInstructions.push(handle);
        variableUsed.put(key, true);
    }

    private void checkLoad(InstructionHandle handle) {
        loadInstructions.push(handle); // pushes the load instruction onto the stack.
    }

    private void checkStore(InstructionHandle handle) {
        int key = ((StoreInstruction) handle.getInstruction()).getIndex();
        variableUsed.put(key, false); // has not been used yet, so set to false.

        InstructionHandle[] instructions = {loadInstructions.pop(), handle}; // protect the LOAD & STORE Instructions.
        variableInstructions.put(key, instructions);
    }


    // <=========================================== Auxiliary Methods ================================================>

    private boolean getComparisonOutcome(InstructionList instructionList, IfInstruction instruction){
        if (isInstructionComparingWithZero(instruction)) {
            // if its comparing with 0, then only one value is loaded onto the stack (which needs to get removed).
            removeHandle(instructionList, loadInstructions.pop());
            return parseComparisonInstruction(valuesStack.pop(), instruction);
        }
        // usually should be the other way around, but the compiler inverses the instruction (i.e. > becomes <=)
        Number first = valuesStack.pop();
        Number second = valuesStack.pop();
        removePreviousTwoLoadInstructions(instructionList); // else remove the two values that are being compared.
        return parseComparisonInstruction(first, second, instruction);
    }

    // <=========================================== Auxiliary Methods =================================================>

    /** used when performing an operation such as arithmetic or comparison, to basically reduce 3 instructions to 1.
     * 3 instructions being: LOAD X, LOAD Y, OPERATION. into just: LOAD Z, where Z is the result.
     *
     * @param instructionList list of instructions in the method, used to delete the unneeded load statements.
     * @param handle instruction wrapper that contains the instruction that performs the operation.
     * @param value the resultant value from the operation, that requires a Load Instruction.
     */
    private void condenseOperationInstructions(InstructionList instructionList, InstructionHandle handle, Number value) {
        displayLog("[CONDENSING] Condensing Instructions Into LOAD: " + value);
        removePreviousTwoLoadInstructions(instructionList); // remove the 2 LOAD Instructions
        switchInstructionToLoadNumber(handle, value); // creates a load instruction that replaces the operation.
    }

    /** creates a load instruction using the value argument given, and replaces the instruction in handle with it.
     *
     * @param handle an instruction wrapper that holds the instruction to be replaced.
     * @param value a value that the LoadInstruction will contain (i.e. LOAD value)
     */
    private void switchInstructionToLoadNumber(InstructionHandle handle, Number value){
        handle.setInstruction(createLoadInstruction(value, cpgen));
        loadInstructions.push(handle);
        displayLog("[SWITCHED_INSTRUCTION] Switched Instruction Into Load: " + value + " | " + handle.getInstruction());
    }

    //pops the load instructions from the stack, and using that to reference the instructions that need to get deleted.
    //this method is primarily used to remove the load instructions that were used for operations (Arithmetic/Comparison).
    private void removePreviousTwoLoadInstructions(InstructionList instructionList) {
        removeHandle(instructionList, loadInstructions.pop());
        removeHandle(instructionList, loadInstructions.pop());
    }

    //Loads the loop bounds (the first instruction and last instruction of a loop) into an ArrayList.
    private void loadLoopBounds(InstructionList instructionList) {
        loopBounds = new ArrayList<InstructionHandle>();
        for(InstructionHandle handle : instructionList.getInstructionHandles()) {
            if(handle.getInstruction() instanceof GotoInstruction) {
                GotoInstruction instruction = (GotoInstruction) handle.getInstruction(); // casts GoToInstruction
                if (instruction.getTarget().getPosition() < handle.getPosition()){ // if the GoTo leads upwards.
                    loopBounds.add(instruction.getTarget()); // start of loop
                    loopBounds.add(handle); // end of loop (GOTO Instruction)
                }
            }
        }
        displayLog("[LOAD_LOOP_BOUNDS] Loaded Loop Bounds. Number of Loops: " + loopBounds.size()/2);
    }

    /** Method that locates the loop that a given instruction belongs to.
     *
     * @param handle InstructionHandle that has the Instruction that we need to fetch the loop for.
     * @return the first Instruction Handle inside the loop.
     */
    private InstructionHandle locateLoopForInstruction(InstructionHandle handle){
        int instructionPosition = handle.getPosition();
        for (int loopStartBounds = 0; loopStartBounds < loopBounds.size(); loopStartBounds += 2){
            InstructionHandle loopStartInstruction = loopBounds.get(loopStartBounds);
            InstructionHandle loopEndInstruction = loopBounds.get(loopStartBounds+1);

            if (instructionPosition >= loopStartInstruction.getPosition() && instructionPosition < loopEndInstruction.getPosition()){
                displayLog("[LOOP_LOCATED] Loop Located @ " + loopStartInstruction.getInstruction() + " ~ " + loopEndInstruction.getInstruction());
                return loopStartInstruction;
            }
        }
        return null;
    }

    /** Method that detects whether the given variable changes during the loop.
     *
     * @param handle Instruction Wrapper that holds the instruction.
     * @param key the key of the variable.
     * @return true/false to indicate whether the variable changes value during the loop.
     */
    private boolean variableChangesInLoop(InstructionHandle handle, int key){
        InstructionHandle handleInLoop = locateLoopForInstruction(handle);

        while (handleInLoop != null && !(handleInLoop.getInstruction() instanceof GotoInstruction)){
            Instruction instruction = handleInLoop.getInstruction();
            if (instruction instanceof StoreInstruction) {
                if (((StoreInstruction) instruction).getIndex() == key) return true;
            } else if (instruction instanceof IINC){
                if (((IINC) instruction).getIndex() == key) return true;
            }
            handleInLoop = handleInLoop.getNext();
        }
        return false;
    }

    // Removes an instruction from the instruction list.
    private void removeHandle(InstructionList instructionList, InstructionHandle handle) {
        displayLog("[REMOVING] Removing Instruction: " + handle.getInstruction());
        InstructionHandle nextHandle = handle.getNext(); // used to get the next instruction if its a target.
        try {
            instructionList.delete(handle);
        } catch (TargetLostException e) {
            // raised if targeted by a GOTO or If Instruction etc. Update the targeters with the next Instruction.
            for (InstructionHandle target : e.getTargets()) {
                for (InstructionTargeter targeter : target.getTargeters()) targeter.updateTarget(target, nextHandle);
            }
        }
    }

    /** Removes the instructions from two points.
     *
     * @param instructionList the list of instructions.
     * @param handle starting point instruction (where to start deleting from)
     * @param targetHandle end point instruction (where to stop deleting)
     */
    private void removeHandle(InstructionList instructionList, InstructionHandle handle, InstructionHandle targetHandle) {
        try {
            instructionList.delete(handle, targetHandle);
        } catch (TargetLostException ignored){ }
    }

    // <============================================= Helper Methods ==================================================>

    // checks if the Instruction Loads a constant value.
    private static boolean isLoadConstantValueInstruction(Instruction instruction){
        return (instruction instanceof LDC || instruction instanceof LDC2_W ||
                instruction instanceof SIPUSH || instruction instanceof BIPUSH ||
                instruction instanceof ICONST || instruction instanceof FCONST ||
                instruction instanceof DCONST || instruction instanceof LCONST);
    }

    // checks if this instruction is an instruction that gets compared with zero.
    private static boolean isInstructionComparingWithZero(Instruction instruction){
        return instruction instanceof  IFLE || instruction instanceof IFLT || instruction instanceof IFGE ||
                instruction instanceof IFGT || instruction instanceof IFEQ || instruction instanceof IFNE;
    }

    /** Converts Number value into another type. First letter is the starting type, second letter is the target type
     * i.e. I2D means Integer to Double.
     *
     * @param instruction Instruction instance that has the type of conversion.
     * @param value the value to convert.
     * @return converted value.
     */
    private static Number convertValue(Instruction instruction, Number value) {
        if (instruction instanceof I2D || instruction instanceof L2D || instruction instanceof F2D){
            return value.doubleValue();
        } else if (instruction instanceof I2F || instruction instanceof L2F || instruction instanceof D2F){
            return value.doubleValue();
        } else if (instruction instanceof I2L || instruction instanceof D2L || instruction instanceof F2L){
            return value.doubleValue();
        } else if (instruction instanceof D2I || instruction instanceof F2I || instruction instanceof L2I){
            return value.doubleValue();
        }
        throw new IllegalStateException("Instruction not recognised");
    }

    // takes in a value and a instruction that compares with 0, and returns the result
	private static boolean parseComparisonInstruction(Number first, Instruction instruction){
        System.out.println("COMPARING WITH 0: " + first);
    	if (instruction instanceof IFLE) return first.intValue() <= 0;
		else if (instruction instanceof IFLT) return first.intValue() < 0;
		else if (instruction instanceof IFGE) return first.intValue() >= 0;
		else if (instruction instanceof IFGT) return first.intValue() > 0;
		else if (instruction instanceof IFEQ) return first.intValue() == 0;
		else if (instruction instanceof IFNE) return first.intValue() != 0;

		throw new IllegalStateException(String.valueOf(instruction)); // if it is None of these objects then error.
	}

    // takes in 2 values and a instruction that compares with 0, and returns the result
    private static boolean parseComparisonInstruction(Number first, Number second, Instruction instruction){
        System.out.println("COMPARING: " + first + " w/ " + second);
        if (instruction instanceof IF_ICMPLE) return first.intValue() <= second.intValue();
        else if (instruction instanceof IF_ICMPLT) return first.intValue() < second.intValue();
        else if (instruction instanceof IF_ICMPGE) return first.intValue() >= second.intValue();
        else if (instruction instanceof IF_ICMPGT) return first.intValue() > second.intValue();
        else if (instruction instanceof IF_ICMPEQ) return first.intValue() == second.intValue();
        else if (instruction instanceof IF_ICMPNE) return first.intValue() != second.intValue();

        throw new IllegalStateException(String.valueOf(instruction)); // if it is None of these objects then error.
    }

    /** This method creates a load instruction using the value that was given to it.
     * LDC2_W is for Doubles/Longs | LDC is for Floats/Integers.
     *
     * @param value a Number object that represents a value.
     * @return an Load Instruction that loads the given number value.
     */
	private static Instruction createLoadInstruction(Number value, ConstantPoolGen cpgen){
		if (value instanceof Double){
			return new LDC2_W(cpgen.addDouble((Double) value)); // pushes double
		} else if (value instanceof Integer){
		    int int_value = (Integer) value;
		    if (int_value >= -1 && int_value <= 5) return new ICONST(int_value);
			return new LDC(cpgen.addInteger((Integer) value)); // pushes integer.
		} else if (value instanceof Long){
			return new LDC2_W(cpgen.addLong((Long) value)); // pushes long
		} else if (value instanceof Float){
			return new LDC(cpgen.addFloat((Float) value)); // pushes float.
		}
		throw new IllegalStateException("Illegal Value");
	}

    /** Gets the value that is to be loaded from a load instruction.
	 *
	 * @param nextInstruction the LoadInstruction that holds the value to be loaded.
	 * @return a Number object that represents the value.
	 */
	private static Number getLoadConstantValue(Instruction nextInstruction, ConstantPoolGen cpgen) {
		if (nextInstruction instanceof LDC) {
		    // LDC loads a integer/float onto the stack.
			return (Number) ((LDC) nextInstruction).getValue(cpgen);
		} else if (nextInstruction instanceof LDC2_W) {
		    // LDC2_W loads a long/double onto the stack.
			return ((LDC2_W) nextInstruction).getValue(cpgen);
		} else if (nextInstruction instanceof BIPUSH) {
		    // BIPUSH loads a byte onto the stack.
			return ((BIPUSH) nextInstruction).getValue();
		} else if (nextInstruction instanceof SIPUSH) {
		    // SIPUSH loads a short onto the stack.
			return ((SIPUSH) nextInstruction).getValue();
		} else if (nextInstruction instanceof ICONST){
		    // ICONST loads an integer constant (value between -1 and 5 inclusive).
			return ((ICONST) nextInstruction).getValue();
		} else if (nextInstruction instanceof FCONST){
		    // FCONST loads a float constant (0.0 or 1.0 or 2.0).
			return ((FCONST) nextInstruction).getValue();
		} else if (nextInstruction instanceof DCONST){
		    // DCONST loads a double constant (0.0 or 1.0).
			return ((DCONST) nextInstruction).getValue();
		} else if (nextInstruction instanceof LCONST){
		    // LCONST loads a long constant (0 or 1).
			return ((LCONST) nextInstruction).getValue();
		}
		return null;
	}

	/**Performs an arithmetic operation using the popping the first 2 values in the stack, and pushing the combined val.
	 *
	 * @param nextInstruction the instruction that indicates the type of arithmetic operation.
	 */
	private static Number performArithmeticOperation(Number first, Number second, Instruction nextInstruction) {
		Number combinedValue ;

		// I represents Integer / D represents Double / F represents Float / L represents Long.
        // 4 possible operations (ADD / SUB / MUL / DIV).

		// <------ Integer Operations ------>
		if (nextInstruction instanceof IADD){
			combinedValue = first.intValue() + second.intValue();
		} else if (nextInstruction instanceof ISUB){
			combinedValue = first.intValue() - second.intValue();
		} else if (nextInstruction instanceof IMUL){
			combinedValue = first.intValue() * second.intValue();
		} else if (nextInstruction instanceof IDIV){
			combinedValue = first.intValue() / second.intValue();
		}

		// <------ Double Operations ------>
		else if (nextInstruction instanceof DADD){
			combinedValue = first.doubleValue() + second.doubleValue();
		} else if (nextInstruction instanceof DSUB){
			combinedValue = first.doubleValue() - second.doubleValue();
		} else if (nextInstruction instanceof DMUL){
			combinedValue = first.doubleValue() * second.doubleValue();
		} else if (nextInstruction instanceof DDIV){
			combinedValue = first.doubleValue() / second.doubleValue();
		}

		// <------ Float Operations ------>
		else if (nextInstruction instanceof FADD){
			combinedValue = first.floatValue() + second.floatValue();
		} else if (nextInstruction instanceof FSUB){
			combinedValue = first.floatValue() - second.floatValue();
		} else if (nextInstruction instanceof FMUL){
			combinedValue = first.floatValue() * second.floatValue();
		} else if (nextInstruction instanceof FDIV){
			combinedValue = first.floatValue() / second.floatValue();
		}

		// <------ Long Operations ------>
		else if (nextInstruction instanceof LADD){
			combinedValue = first.longValue() + second.longValue();
		} else if (nextInstruction instanceof LSUB){
			combinedValue = first.longValue() - second.longValue();
		} else if (nextInstruction instanceof LMUL){
			combinedValue = first.longValue() * second.longValue();
		} else if (nextInstruction instanceof LDIV){
			combinedValue = first.longValue() / second.longValue();
		}

		else throw new IllegalStateException("Unrecognised Arithmetic Operation");
        return combinedValue;

	}

    /** @noinspection WeakerAccess */
	public void write(String optimisedFilePath) {
        this.optimize();

        try {
            FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
            this.optimized.dump(out);
        } catch (FileNotFoundException e) {
            // Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // Auto-generated catch block
            e.printStackTrace();
        }
    }
}
