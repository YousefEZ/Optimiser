package comp0012.main;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;

class PeepHole {

    private ClassGen cgen;
    private ConstantPoolGen cpgen;
    private Stack<InstructionHandle> loadInstructions;
    private HashMap<Integer, Boolean> variables;
    private HashMap<Integer, InstructionHandle[]> variableInstructions;
    private ArrayList<Integer> loopBounds;
    private boolean lastInstructionWasLoad;
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
        System.out.println(instructionList);

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
        System.out.println("OUTPUTTING REMAINDER LOAD INSTRUCTIONS");
        System.out.println(loadInstructions);

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

    private boolean instructionIsInALoop(InstructionHandle handle){
        return locateLoopForInstruction(handle) != -1;
    }

    private int locateLoopForInstruction(InstructionHandle handle){
        int instructionPosition = handle.getPosition();
        for (int loopStartBounds = 0; loopStartBounds < loopBounds.size(); loopStartBounds += 2){
            if (instructionPosition > loopBounds.get(loopStartBounds)  && instructionPosition < loopBounds.get(loopStartBounds+1)){
                return loopBounds.get(loopStartBounds);
            }
        }
        return -1;
    }

    private boolean variableChangesInLoop(InstructionList instructionList, InstructionHandle handle, int key){
        int loopStart = locateLoopForInstruction(handle);
        InstructionHandle handleInLoop = instructionList.findHandle(loopStart);
        while (!(handleInLoop.getInstruction() instanceof GotoInstruction)){
            Instruction instruction = handleInLoop.getInstruction();
            if (instruction instanceof StoreInstruction && ((StoreInstruction) instruction).getIndex() == key) return true;
            handleInLoop = handleInLoop.getNext();
        }
        return false;
    }


    /** handles the instruction inside of the InstructionHandle by first checking its type then optimising it.
     *
     * @param handle wrapper that contains the instruction.
     * @param instructionList list of all the instruction, this is required because some changes are made here.
     */
    private void handleInstruction(InstructionHandle handle, InstructionList instructionList){
        Instruction instruction = handle.getInstruction(); // gets the instruction from the instruction handle.

        // Load Instructions
        if (isLoadConstantValueInstruction(instruction)) handleLoad(handle, instructionList);
        else if (instruction instanceof LoadInstruction) handleVariableLoad(handle, instructionList);
        else lastInstructionWasLoad = false;

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
    private void handleVariableLoad(InstructionHandle handle, InstructionList instructionList) {
        //if (lastInstructionWasLoad) removeHandle(instructionList, loadInstructions.pop());
        System.out.println("PUSHING VARIABLE LOAD: " + handle);
        loadInstructions.push(handle);
        variables.put(((LoadInstruction) handle.getInstruction()).getIndex(), true);
        lastInstructionWasLoad = true;
    }

    // Method that handles loading in values from a LoadInstruction.
    private void handleLoad(InstructionHandle handle, InstructionList instructionList) {
        System.out.println("PUSHING CONSTANT LOAD: " + handle);
        loadInstructions.push(handle); // pushes the load instruction onto the stack.
        lastInstructionWasLoad = true;
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
        System.out.println("REMOVING INSTRUCTION -> " + handle.getInstruction() + " POSITION => " + handle.getPosition());
        InstructionHandle nextHandle = handle.getNext();
        try {
            instructionList.delete(handle);
        } catch (TargetLostException e) {
            System.out.println("HANDLE POSITION -> " + handle.getPosition());
            System.out.println("REDIRECTING BRANCHES TO: " + nextHandle.getInstruction());
            //instructionList.redirectBranches(handle, nextHandle);

            InstructionHandle[] targets = e.getTargets();

            for (InstructionHandle target : targets) {
                //instructionList.redirectBranches(target, target.getNext());
                //System.out.println(target.getNext());

                InstructionTargeter[] targeters = target.getTargeters();

                for (InstructionTargeter targeter : targeters) { targeter.updateTarget(target, nextHandle);
                }
            }
        }
    }

    private void run(){
        Method[] methods = cgen.getMethods(); // gets all the methods.
        loadInstructions = new Stack<InstructionHandle>();
        variables = new HashMap<Integer, Boolean>();
        variableInstructions = new HashMap<Integer, InstructionHandle[]>();
        loopBounds = new ArrayList<Integer>();
        System.out.println(cgen.getClassName());
        for (Method method : methods) {
            lastInstructionWasLoad = false;
            displayNewMethod(method.getName());
            optimiseMethod(method); // optimizes each method.
            loopBounds.clear();
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
