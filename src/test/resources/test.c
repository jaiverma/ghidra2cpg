#include <stdio.h>
#include <string.h>
void test2(char* x){
  char dest[4096];
  strcpy(dest,x);
}

int test1(int y) {
  printf("test1 function %i\n", y+20);
  char xy[] = "abcdefg";
  test2(xy);
  return y+20;
}

int test(int x) {
  char y[] = "test function";
  printf("%s\n", y);
  test1(x);
  return x+(x*2)+20;
}

int main (int argc, char** argv) {
  printf("main function\n");
  test(20);
  return 0;
}
