package semicolon_out;

public class A_test404 {
	public void foo() {
		/*]*/int x;
		x= extracted();/*[*/
		x= 10;
	}
	protected int extracted() {
		int x= 0;
		return x;
	}
}