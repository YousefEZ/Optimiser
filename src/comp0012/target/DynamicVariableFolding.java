package comp0012.target;

public class DynamicVariableFolding {
    public int methodOne() {
        int a = 42;
        int b = (a + 764) * 3;
        a = b - 67;
        return b + 1234 - a;
    }

    public boolean methodTwo() {
        int x = 12345;
        int y = 54321;
        System.out.println(x < y);
        y = 0;
        return x > y;
    }

    public int methodThree() {
        int i = 0;
        int j = i + 3;
        i = j + 4;
        j = i + 5;
        return i * j;
    }
    
    public int methodFour(){
        int a = 534245;
        int b = a - 1234;
        System.out.println((120298345 - a) * 38.435792873);
        for(int i = 0; i < 10; i++){
            System.out.println((b - a) * i);
        }
        a = 4;
        b = a + 2;
        return a * b;
    }
}