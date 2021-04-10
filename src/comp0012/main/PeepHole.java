package comp0012.main;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.HashMap;
import java.util.Stack;

class PeepHole {

    private ClassGen cgen;
    private ConstantPoolGen cpgen;
    private Stack<InstructionHandle> loadInstructions;
    private HashMap<Integer, Boolean> variables;
    private HashMap<Integer, InstructionHandle[]> variableInstructions;
    private boolean DEBUG;

    private void displayLog(String log) {
        if (DEBUG) System.out.println(log);
    }

    private void displayNewMethod(String methodName){
        displayLog("------------------");
        displayLog("|| NEXT METHOD: ||");
        displayLog(methodName);
        displayLog("-----------------");
    }

    private void displayNextClass(){
        displayLog("-----------------");
        displayLog("||             ||");
        displayLog("|| NEXT CLASS  ||");
        displayLog("||             ||");
        displayLog("-----------------");
    }

    private PeepHole(ClassGen cgen, ConstantPoolGen cpgen){
        this.cgen = cgen;
        this.cpgen = cpgen;
        DEBUG = true;
    }

    private void optimiseMethod(Method method){
        Code methodCode = method.getCode(); // gets the code inside the method.
        InstructionList instructionList = new InstructionList(methodCode.getCode()); // gets code and makes an list of Instructions.
        MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(),
                null, method.getName(), cgen.getClassName(), instructionList, cpgen);

        for (InstructionHandle handle : instructionList.getInstructionHandles()) {
            handleInstruction(handle, instructionList);
        }
        System.out.println("FINISHED METHOD READING");
        for (int key: variables.keySet()){
            if (!variables.get(key)){
                removeHandle(instructionList, variableInstructions.get(key)[0]);
                removeHandle(instructionList, variableInstructions.get(key)[1]);
            }
        }
        System.out.println("FINISHED DEAD CODE REMOVAL");
        System.out.println(instructionList);
        instructionList.setPositions(true);
        replaceMethodCode(method, methodGen);
        System.out.println("METHOD CODE REPLACED");
    }

    // replaces the original method code with the optimized method code.
    private void replaceMethodCode(Method originalMethod, MethodGen methodGen){
        methodGen.setMaxStack();
        methodGen.setMaxLocals();
        Method newMethod = methodGen.getMethod();
        cgen.replaceMethod(originalMethod, newMethod);
    }

    /** handles the instruction inside of the InstructionHandle by first checking its type then optimising it.
     *
     * @param handle wrapper that contains the instruction.
     * @param instructionList list of all the instruction, this is required because some changes are made here.
     */
    private void handleInstruction(InstructionHandle handle, InstructionList instructionList){
        Instruction instruction = handle.getInstruction(); // gets the instruction from the instruction handle.

        // Load Instructions
        if (isLoadConstantValueInstruction(instruction)) handleLoad(handle);
        else if (instruction instanceof LoadInstruction) handleVariableLoad(handle);

        // Store Instructions
        if (instruction instanceof StoreInstruction) handleStore(handle);
    }

    // checks if the Instruction Loads a constant value.
    private boolean isLoadConstantValueInstruction(Instruction instruction){
        return (instruction instanceof LDC || instruction instanceof LDC2_W ||
                instruction instanceof SIPUSH || instruction instanceof BIPUSH ||
                instruction instanceof ICONST || instruction instanceof FCONST ||
                instruction instanceof DCONST || instruction instanceof LCONST || instruction instanceof ALOAD);
    }

    // Method that handles loading in values from variables.
    private void handleVariableLoad(InstructionHandle handle) {
        System.out.println("PUSHING VARIABLE LOAD: " + handle);
        loadInstructions.push(handle);
        variables.put(((LoadInstruction) handle.getInstruction()).getIndex(), true);
    }

    // Method that handles loading in values from a LoadInstruction.
    private void handleLoad(InstructionHandle handle) {
        System.out.println("PUSHING CONSTANT LOAD: " + handle);
        loadInstructions.push(handle); // pushes the load instruction onto the stack.
    }

    // Method that handles storing values into a variable.
    private void handleStore(InstructionHandle handle) {
        System.out.println("STORING VALUE ONTO VARIABLE: " + handle);
        int index = ((StoreInstruction) handle.getInstruction()).getIndex();
        variables.put(index, false);
        InstructionHandle[] instructions = {handle, loadInstructions.pop()};
        variableInstructions.put(index, instructions);
    }

    private void removeHandle(InstructionList instructionList, InstructionHandle handle) {
        if (handle == null) return;
        try {
            instructionList.delete(handle);
        } catch (TargetLostException e) {
            InstructionHandle[] targets = e.getTargets();

            for (InstructionHandle target : targets) {
                InstructionTargeter[] targeters = target.getTargeters();

                for (InstructionTargeter targeter : targeters) targeter.updateTarget(target, null);
            }
        }
    }

    private void run(){
        Method[] methods = cgen.getMethods(); // gets all the methods.
        loadInstructions = new Stack<InstructionHandle>();
        variables = new HashMap<Integer, Boolean>();
        variableInstructions = new HashMap<Integer, InstructionHandle[]>();
        System.out.println(cgen.getClassName());
        for (Method method : methods) {
            displayNewMethod(method.getName());
            optimiseMethod(method); // optimizes each method.
            loadInstructions.clear();
            variables.clear(); // clears variables for next method.
            variableInstructions.clear();
        }
        displayNextClass();
    }

    public static void optimise(ClassGen cgen, ConstantPoolGen cpgen){
        PeepHole peepHole = new PeepHole(cgen, cpgen);
        peepHole.run();
    }
}
