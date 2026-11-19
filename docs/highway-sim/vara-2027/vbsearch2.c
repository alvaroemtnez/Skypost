// More VB6 Rnd hypotheses over all 2^24 start states.
//  stride K M v...   : value = Int(Rnd*M) taken every K-th draw (K=1..)
//  round  M v...     : value = CInt(Rnd*M) (banker's rounding)
//  fy1 N v...        : a=0..N-1; for i=N-1..1: j=Int(Rnd*(i+1)); swap a[i],a[j]   (checks final perm)
//  fy2 N v...        : for i=0..N-2: j=i+Int(Rnd*(N-i)); swap
//  fy3 N v...        : for i=0..N-1: j=Int(Rnd*N); swap
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define MASK 0xFFFFFFu
static inline uint32_t adv(uint32_t s){return (s*0xFD43FDu+0xC39EC3u)&MASK;}
static inline float rnd(uint32_t*s){*s=adv(*s);return (float)*s/16777216.0f;}
int main(int c,char**v){
  const char*mode=v[1]; int a1=atoi(v[2]); int argo=3; int K=1;
  if(!strcmp(mode,"stride")){K=a1;a1=atoi(v[3]);argo=4;}
  int nt=c-argo; static int t[100000]; for(int i=0;i<nt;i++)t[i]=atoi(v[argo+i]);
  long hits=0; static int a[100000]; int N=a1;
  for(uint32_t s0=0;s0<=MASK;s0++){
    uint32_t s=s0; int ok=1;
    if(!strcmp(mode,"stride")){
      for(int i=0;i<nt&&ok;i++){float r=0;for(int k=0;k<K;k++)r=rnd(&s); if((int)(r*(float)a1)!=t[i])ok=0;}
    } else if(!strcmp(mode,"round")){
      for(int i=0;i<nt&&ok;i++){float r=rnd(&s); if((int)nearbyintf(r*(float)a1)!=t[i])ok=0;}
    } else {
      for(int i=0;i<N;i++)a[i]=i;
      if(!strcmp(mode,"fy1")){ for(int i=N-1;i>=1&&ok;i--){int j=(int)(rnd(&s)*(float)(i+1)); int x=a[i];a[i]=a[j];a[j]=x; if(a[i]!=t[i])ok=0;} }
      else if(!strcmp(mode,"fy2")){ for(int i=0;i<N-1&&ok;i++){int j=i+(int)(rnd(&s)*(float)(N-i)); int x=a[i];a[i]=a[j];a[j]=x; if(a[i]!=t[i])ok=0;} }
      else { for(int i=0;i<N;i++){int j=(int)(rnd(&s)*(float)N); int x=a[i];a[i]=a[j];a[j]=x;} for(int i=0;i<N;i++) if(a[i]!=t[i]){ok=0;break;} }
    }
    if(ok){printf("match 0x%06X\n",s0); if(++hits>5)break;}
  }
  printf("hits=%ld\n",hits); return 0;}
